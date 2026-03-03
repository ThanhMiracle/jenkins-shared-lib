def call(Map cfg = [:]) {
  // allow override defaults from Jenkinsfile if needed
  def dockerCredsId = cfg.dockerCredsId ?: 'dockerhub-creds'
  def composeFiles  = cfg.composeFiles  ?: ['docker-compose.yml', 'docker-compose.ci.yml']

  pipeline {
    agent any

    options {
      timestamps()
      disableConcurrentBuilds()
    }

    environment {
      DOCKER_BUILDKIT = "1"
      COMPOSE_DOCKER_CLI_BUILD = "1"
      COMPOSE_PROJECT_NAME = "microshop-ci-${env.BUILD_NUMBER}"
    }

    stages {
      stage('Checkout') {
        steps { checkout scm }   // IMPORTANT: checks out micro-ecom (your app repo)
      }

      stage('Preflight') {
        steps {
          sh '''#!/usr/bin/env bash
            set -euo pipefail
            echo "================ PRE-FLIGHT ================"
            echo "Branch: ${BRANCH_NAME}"
            echo "Node: $(hostname)"
            echo "User: $(id)"
            echo "Workspace: $PWD"
            echo "COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}"
            echo

            echo "== Docker Info =="
            docker version

            echo "== Docker Compose Info =="
            if docker compose version >/dev/null 2>&1; then
              docker compose version
              echo "Using docker compose v2"
            elif docker-compose version >/dev/null 2>&1; then
              docker-compose version
              echo "Using docker-compose v1"
            else
              echo "ERROR: docker compose not found"
              exit 1
            fi

            echo "== DNS sanity (host) =="
            getent hosts api.github.com || true
          '''
        }
      }

      stage('Build') {
        steps {
          sh """#!/usr/bin/env bash
            set -euxo pipefail
            echo "================ BUILD ================"

            if docker compose version >/dev/null 2>&1; then
              COMPOSE="docker compose"
            else
              COMPOSE="docker-compose"
            fi

            C="\$COMPOSE -p ${COMPOSE_PROJECT_NAME} ${composeFiles.collect{ "-f ${it}" }.join(' ')}"

            echo "== Validating Compose Config =="
            \$C config

            echo "== Pulling base images =="
            \$C pull --ignore-pull-failures || true

            echo "== Building images =="
            \$C build --pull

            echo "== Built images =="
            \$C images || true
          """
        }
      }

      stage('Test (dev only)') {
        when { branch 'dev' }
        steps {
          sh """#!/usr/bin/env bash
            set -euxo pipefail
            echo "================ TEST (DEV) ================"

            if docker compose version >/dev/null 2>&1; then
              COMPOSE="docker compose"
            else
              COMPOSE="docker-compose"
            fi

            C="\$COMPOSE -p ${COMPOSE_PROJECT_NAME} ${composeFiles.collect{ "-f ${it}" }.join(' ')}"

            echo "== Starting shared services =="
            \$C up -d postgres rabbit mailhog

            echo "== Waiting for healthchecks (if supported) =="
            \$C up -d --wait postgres rabbit mailhog || true

            echo "== Current compose ps =="
            \$C ps || true

            run_test () {
              svc="\$1"
              echo
              echo "---------- RUN TEST: \${svc} ----------"

              mkdir -p ci-artifacts

              set +e
              \$C run --rm "\${svc}" 2>&1 | tee "ci-artifacts/\${svc}.out.log"
              rc=\${PIPESTATUS[0]}
              set -e

              echo "---------- RESULT: \${svc} exit=\${rc} ----------"

              if [ "\${rc}" -ne 0 ]; then
                echo
                echo "!!!!! TEST FAILED: \${svc} (exit=\${rc}) !!!!!"
                echo "== Compose ps (on failure) =="
                \$C ps || true

                echo "== Compose logs tail (on failure) =="
                \$C logs --no-color --tail=300 || true

                exit "\${rc}"
              fi
            }

            run_test auth-tests
            run_test product-tests
            run_test order-tests
            run_test payment-tests
            run_test notify-tests
          """
        }
      }

      stage('Push Images (main only)') {
        when { branch 'main' }
        steps {
          withCredentials([usernamePassword(credentialsId: dockerCredsId, usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS')]) {
            sh """#!/usr/bin/env bash
              set -euxo pipefail
              echo "================ PUSH (MAIN / DOCKER HUB) ================"

              if docker compose version >/dev/null 2>&1; then
                COMPOSE="docker compose"
              else
                COMPOSE="docker-compose"
              fi

              C="\$COMPOSE -p ${COMPOSE_PROJECT_NAME} ${composeFiles.collect{ "-f ${it}" }.join(' ')}"

              mkdir -p ci-artifacts

              GIT_SHA="\$(git rev-parse --short=12 HEAD)"
              echo "GIT_SHA=\$GIT_SHA" | tee ci-artifacts/git-sha.txt

              echo "== Docker Hub login =="
              echo "\$DOCKERHUB_PASS" | docker login -u "\$DOCKERHUB_USER" --password-stdin

              echo "== Images from compose =="
              \$C config --images | sort -u | tee ci-artifacts/compose-images.txt

              while read -r img; do
                [ -z "\$img" ] && continue

                base="\${img%:*}"
                tag="\${img##*:}"
                if [ "\$base" = "\$img" ]; then
                  base="\$img"
                  tag="latest"
                fi

                repo="\$(echo "\$base" | awk -F/ '{print \$NF}')"
                dest_base="\${DOCKERHUB_USER}/\${repo}"

                dest_sha="\${dest_base}:\${GIT_SHA}"
                dest_main_latest="\${dest_base}:main-latest"

                echo "== Tagging \$img -> \$dest_sha and \$dest_main_latest =="
                docker tag "\$img" "\$dest_sha"
                docker tag "\$img" "\$dest_main_latest"

                echo "== Pushing \$dest_sha =="
                docker push "\$dest_sha"

                echo "== Pushing \$dest_main_latest =="
                docker push "\$dest_main_latest"
              done < <(\$C config --images | sort -u)

              docker logout || true
            """
          }
        }
      }

      stage('Deploy (main only) - later') {
        when { branch 'main' }
        steps {
          sh '''#!/usr/bin/env bash
            set -euo pipefail
            echo "================ DEPLOY (MAIN) ================"
            echo "Deploy will be added later."
          '''
        }
      }
    }

    post {
      always {
        sh """#!/usr/bin/env bash
          set +e
          echo "================ POST / ALWAYS ================"

          if docker compose version >/dev/null 2>&1; then
            COMPOSE="docker compose"
          else
            COMPOSE="docker-compose"
          fi

          C="\$COMPOSE -p ${COMPOSE_PROJECT_NAME} ${composeFiles.collect{ "-f ${it}" }.join(' ')}"

          mkdir -p ci-artifacts

          echo "== Collecting logs =="
          \$C ps > ci-artifacts/compose-ps.txt 2>&1 || true
          \$C logs --no-color > ci-artifacts/compose-logs.txt 2>&1 || true
          \$C config > ci-artifacts/compose-config.rendered.yml 2>&1 || true

          echo "== Docker summary (host) =="
          docker ps -a > ci-artifacts/docker-ps-a.txt 2>&1 || true
          docker network ls > ci-artifacts/docker-networks.txt 2>&1 || true

          echo "== Cleaning up =="
          \$C down -v --remove-orphans || true
        """

        archiveArtifacts artifacts: 'ci-artifacts/*', allowEmptyArchive: true
        cleanWs()
      }
    }
  }
}