def call(Map cfg = [:]) {
  // ===== Config (override from Jenkinsfile) =====
  def dockerCredsId = (cfg.dockerCredsId ?: 'dockerhub-creds').toString()
  def composeFiles  = (cfg.composeFiles  ?: ['docker-compose.yml', 'docker-compose.ci.yml']) as List

  // Branch controls
  def testBranch   = (cfg.testBranch   ?: 'dev').toString()    // run tests only on this branch
  def pushBranch   = (cfg.pushBranch   ?: 'main').toString()   // push only on this branch
  def deployBranch = (cfg.deployBranch ?: 'main').toString()   // deploy placeholder on this branch

  // Services to start for tests (infra)
  def infraServices = (cfg.infraServices ?: ['postgres', 'rabbit', 'mailhog']) as List
  // Test runner services (compose services that run pytest)
  def testServices  = (cfg.testServices  ?: ['auth-tests', 'product-tests', 'order-tests', 'payment-tests', 'notify-tests']) as List

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
        steps { checkout scm }
      }

      stage('Preflight') {
        steps {
          sh '''#!/usr/bin/env bash
            set -euo pipefail
            echo "================ PRE-FLIGHT ================"
            echo "Branch: ${BRANCH_NAME:-unknown}"
            echo "Node: $(hostname)"
            echo "User: $(id)"
            echo "Workspace: $PWD"
            echo "COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}"
            echo

            echo "== Docker Info =="
            docker version || true

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

            COMPOSE="\$(docker compose version >/dev/null 2>&1 && echo 'docker compose' || echo 'docker-compose')"
            C="\$COMPOSE -p \$COMPOSE_PROJECT_NAME ${composeFiles.collect { "-f ${it}" }.join(' ')}"

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

      // Stage names MUST be static in Declarative Pipeline
      stage('Test') {
        when { expression { env.BRANCH_NAME == testBranch } }
        steps {
          sh """#!/usr/bin/env bash
            set -euxo pipefail
            echo "================ TEST (only: ${testBranch.toUpperCase()}) ================"
            echo "Branch is: \$BRANCH_NAME"

            COMPOSE="\$(docker compose version >/dev/null 2>&1 && echo 'docker compose' || echo 'docker-compose')"
            C="\$COMPOSE -p \$COMPOSE_PROJECT_NAME ${composeFiles.collect { "-f ${it}" }.join(' ')}"

            mkdir -p ci-artifacts

            echo "== Starting shared services: ${infraServices.join(' ')} =="
            \$C up -d ${infraServices.join(' ')}

            echo "== Waiting for healthchecks (if supported) =="
            \$C up -d --wait ${infraServices.join(' ')} || true

            echo "== Current compose ps =="
            \$C ps || true

            run_test () {
              svc="\$1"
              echo
              echo "---------- RUN TEST: \${svc} ----------"

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

            ${testServices.collect { "run_test ${it}" }.join('\n            ')}
          """
        }
      }

      stage('Push Images') {
        when { expression { env.BRANCH_NAME == pushBranch } }
        steps {
          withCredentials([usernamePassword(credentialsId: dockerCredsId, usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS')]) {
            sh """#!/usr/bin/env bash
              set -euxo pipefail
              echo "================ PUSH (only: ${pushBranch.toUpperCase()} / DOCKER HUB) ================"
              echo "Branch is: \$BRANCH_NAME"

              COMPOSE="\$(docker compose version >/dev/null 2>&1 && echo 'docker compose' || echo 'docker-compose')"
              C="\$COMPOSE -p \$COMPOSE_PROJECT_NAME ${composeFiles.collect { "-f ${it}" }.join(' ')}"

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
                if [ "\$base" = "\$img" ]; then
                  base="\$img"
                fi

                repo="\$(echo "\$base" | awk -F/ '{print \$NF}')"
                dest_base="\${DOCKERHUB_USER}/\${repo}"

                dest_sha="\${dest_base}:\${GIT_SHA}"
                dest_branch_latest="\${dest_base}:${pushBranch}-latest"

                echo "== Tagging \$img -> \$dest_sha and \$dest_branch_latest =="
                docker tag "\$img" "\$dest_sha"
                docker tag "\$img" "\$dest_branch_latest"

                echo "== Pushing \$dest_sha =="
                docker push "\$dest_sha"

                echo "== Pushing \$dest_branch_latest =="
                docker push "\$dest_branch_latest"
              done < <(\$C config --images | sort -u)

              docker logout || true
            """
          }
        }
      }

      stage('Deploy') {
        when { expression { env.BRANCH_NAME == deployBranch } }
        steps {
          sh """#!/usr/bin/env bash
            set -euo pipefail
            echo "================ DEPLOY (only: ${deployBranch.toUpperCase()}) ================"
            echo "Branch is: \$BRANCH_NAME"
            echo "Deploy will be added later."
          """
        }
      }
    }

    post {
      always {
        sh """#!/usr/bin/env bash
          set +e
          echo "================ POST / ALWAYS ================"

          COMPOSE="\$(docker compose version >/dev/null 2>&1 && echo 'docker compose' || echo 'docker-compose')"
          C="\$COMPOSE -p \$COMPOSE_PROJECT_NAME ${composeFiles.collect { "-f ${it}" }.join(' ')}"

          mkdir -p ci-artifacts

          echo "== Collecting logs =="
          \$C ps > ci-artifacts/compose-ps.txt 2>&1 || true
          \$C logs --no-color > ci-artifacts/compose-logs.txt 2>&1 || true
          \$C config > ci-artifacts/compose-config.rendered.yml 2>&1 || true

          echo "== Docker summary (host) =="
          docker ps -a > ci-artifacts/docker-ps-a.txt 2>&1 || true
          docker network ls > ci-artifacts/docker-networks.txt 2>&1 || true

          echo "== Cleaning up compose project =="
          \$C down -v --remove-orphans || true
        """

        archiveArtifacts artifacts: 'ci-artifacts/*', allowEmptyArchive: true
        cleanWs()
      }
    }
  }
}