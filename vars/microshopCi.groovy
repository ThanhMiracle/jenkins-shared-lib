def call(Map cfg = [:]) {
  // ===== Config (override from Jenkinsfile) =====
  final String dockerCredsId = (cfg.dockerCredsId ?: 'dockerhub-creds').toString()
  final List<String> composeFiles = (cfg.composeFiles ?: ['docker-compose.yml', 'docker-compose.ci.yml']) as List<String>

  // Branch controls
  final String testBranch   = (cfg.testBranch   ?: 'dev').toString()
  final String pushBranch   = (cfg.pushBranch   ?: 'main').toString()
  final String deployBranch = (cfg.deployBranch ?: 'main').toString()

  // Services to start for tests (infra)
  final List<String> infraServices = (cfg.infraServices ?: ['postgres', 'rabbit', 'mailhog']) as List<String>
  // Test runner services (compose services that run pytest)
  final List<String> testServices  = (cfg.testServices  ?: ['auth-tests', 'product-tests', 'order-tests', 'payment-tests', 'notify-tests']) as List<String>

  // Optional: run test services in parallel (true/false)
  final boolean parallelTests = (cfg.parallelTests == null ? false : cfg.parallelTests as boolean)

  // Helper: figure out branch name for both Multibranch + freestyle
  // (env.BRANCH_NAME is Multibranch; env.GIT_BRANCH may be "origin/dev")
  def resolvedBranchExpr = '''
    BR="${BRANCH_NAME:-}"
    if [ -z "$BR" ] && [ -n "${GIT_BRANCH:-}" ]; then
      BR="${GIT_BRANCH#origin/}"
      BR="${BR#refs/heads/}"
    fi
    echo "$BR"
  '''.stripIndent().trim()

  pipeline {
    agent any

    options {
      timestamps()
      disableConcurrentBuilds()
      // ansiColor('xterm') // enable if you have AnsiColor plugin
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
          sh """#!/usr/bin/env bash
            set -euo pipefail
            echo "================ PRE-FLIGHT ================"
            BRANCH="\$(${resolvedBranchExpr})"
            echo "Resolved branch: \${BRANCH:-unknown}"
            echo "Node: \$(hostname)"
            echo "User: \$(id)"
            echo "Workspace: \$PWD"
            echo "COMPOSE_PROJECT_NAME=\$COMPOSE_PROJECT_NAME"
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
          """
        }
      }

      stage('Build') {
        steps {
          sh """#!/usr/bin/env bash
            set -euxo pipefail
            echo "================ BUILD ================"

            if docker compose version >/dev/null 2>&1; then
              COMPOSE=(docker compose)
            else
              COMPOSE=(docker-compose)
            fi

            FILES=()
            ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n            ')}

            echo "== Validating Compose Config =="
            "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" config

            echo "== Pulling base images (best-effort) =="
            "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" pull --ignore-pull-failures || true

            echo "== Building images =="
            "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" build --pull

            echo "== Built images =="
            "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" images || true
          """
        }
      }

      stage('Test') {
        when {
          expression {
            def b = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '')
            b = b.replaceFirst(/^origin\\//, '').replaceFirst(/^refs\\/heads\\//, '')
            return b == testBranch
          }
        }
        steps {
          script {
            if (!parallelTests) {
              sh """#!/usr/bin/env bash
                set -euxo pipefail
                echo "================ TEST (only: ${testBranch.toUpperCase()}) ================"

                if docker compose version >/dev/null 2>&1; then
                  COMPOSE=(docker compose)
                else
                  COMPOSE=(docker-compose)
                fi

                FILES=()
                ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n                ')}

                mkdir -p ci-artifacts

                echo "== Starting infra: ${infraServices.join(' ')} =="
                "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" up -d ${infraServices.join(' ')}

                echo "== Waiting for healthchecks (best-effort) =="
                "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" up -d --wait ${infraServices.join(' ')} || true

                echo "== Current compose ps =="
                "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" ps || true

                run_test () {
                  svc="\$1"
                  echo
                  echo "---------- RUN TEST: \${svc} ----------"

                  set +e
                  "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" run --rm "\${svc}" 2>&1 | tee "ci-artifacts/\${svc}.out.log"
                  rc=\${PIPESTATUS[0]}
                  set -e

                  echo "---------- RESULT: \${svc} exit=\${rc} ----------"

                  if [ "\${rc}" -ne 0 ]; then
                    echo
                    echo "!!!!! TEST FAILED: \${svc} (exit=\${rc}) !!!!!"
                    echo "== Compose ps (on failure) =="
                    "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" ps || true

                    echo "== Compose logs tail (on failure) =="
                    "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" logs --no-color --tail=300 || true

                    exit "\${rc}"
                  fi
                }

                ${testServices.collect { "run_test ${it}" }.join('\n                ')}
              """
            } else {
              // Parallel mode: start infra once, then run tests in parallel
              sh """#!/usr/bin/env bash
                set -euxo pipefail
                echo "== Starting infra for parallel tests: ${infraServices.join(' ')} =="

                if docker compose version >/dev/null 2>&1; then
                  COMPOSE=(docker compose)
                else
                  COMPOSE=(docker-compose)
                fi

                FILES=()
                ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n                ')}

                mkdir -p ci-artifacts
                "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" up -d ${infraServices.join(' ')}
                "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" up -d --wait ${infraServices.join(' ')} || true
                "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" ps || true
              """

              def branches = [:]
              for (String svc : testServices) {
                branches[svc] = {
                  sh """#!/usr/bin/env bash
                    set -euxo pipefail
                    echo "---------- RUN TEST (parallel): ${svc} ----------"

                    if docker compose version >/dev/null 2>&1; then
                      COMPOSE=(docker compose)
                    else
                      COMPOSE=(docker-compose)
                    fi

                    FILES=()
                    ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n                    ')}

                    mkdir -p ci-artifacts
                    "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" run --rm '${svc}' 2>&1 | tee "ci-artifacts/${svc}.out.log"
                  """
                }
              }

              parallel branches
            }
          }
        }
      }

      stage('Push Images') {
        when {
          expression {
            def b = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '')
            b = b.replaceFirst(/^origin\\//, '').replaceFirst(/^refs\\/heads\\//, '')
            return b == pushBranch
          }
        }
        steps {
          withCredentials([usernamePassword(credentialsId: dockerCredsId, usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS')]) {
            sh """#!/usr/bin/env bash
              set -euxo pipefail
              echo "================ PUSH (only: ${pushBranch.toUpperCase()} / DOCKER HUB) ================"

              if docker compose version >/dev/null 2>&1; then
                COMPOSE=(docker compose)
              else
                COMPOSE=(docker-compose)
              fi

              FILES=()
              ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n        ')}

              mkdir -p ci-artifacts

              GIT_SHA="\$(git rev-parse --short=12 HEAD)"
              echo "GIT_SHA=\$GIT_SHA" | tee ci-artifacts/git-sha.txt

              echo "\$DOCKERHUB_PASS" | docker login -u "\$DOCKERHUB_USER" --password-stdin

              "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" config --images | sort -u | tee ci-artifacts/compose-images.txt

              while read -r img; do
                [ -z "\$img" ] && continue

                if ! docker image inspect "\$img" >/dev/null 2>&1; then
                  echo "ERROR: local image not found: \$img"
                  exit 1
                fi

                base="\${img%:*}"
                [ "\$base" = "\$img" ] && base="\$img"

                repo="\${base##*/}"
                dest_base="\${DOCKERHUB_USER}/\${repo}"

                dest_sha="\${dest_base}:\${GIT_SHA}"
                dest_branch_latest="\${dest_base}:${pushBranch}-latest"

                echo "== Tagging \$img -> \$dest_sha and \$dest_branch_latest =="
                docker tag "\$img" "\$dest_sha"
                docker tag "\$img" "\$dest_branch_latest"

                docker push "\$dest_sha"
                docker push "\$dest_branch_latest"
              done < <("\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" config --images | sort -u)

              docker logout || true
            """
          }
        }
      }

      stage('Deploy') {
        when {
          expression {
            def b = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '')
            b = b.replaceFirst(/^origin\//, '').replaceFirst(/^refs\/heads\//, '')
            return b == deployBranch
          }
        }
        steps {
          withCredentials([usernamePassword(credentialsId: dockerCredsId, usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS')]) {
            sshagent(['ec2-ssh']) {
              sh """#!/usr/bin/env bash
                set -euo pipefail

                : "\${BASTION_HOST:?BASTION_HOST is required}"
                : "\${PRIVATE_EC2_IP:?PRIVATE_EC2_IP is required}"
                : "\${DOCKERHUB_USER:?DOCKERHUB_USER is required}"
                : "\${DOCKERHUB_PASS:?DOCKERHUB_PASS is required}"

                BASTION_USER="ec2-user"
                BASTION_HOST="\${BASTION_HOST}"
                APP_USER="ec2-user"
                APP_HOST="\${PRIVATE_EC2_IP}"
                APP_DIR="/home/ec2-user/app"
                GIT_SHA="\$(git rev-parse --short=12 HEAD)"

                echo "================ DEPLOY ================"
                echo "Deploy branch: ${deployBranch}"
                echo "Target app host: \$APP_HOST"
                echo "App dir: \$APP_DIR"
                echo "GIT_SHA: \$GIT_SHA"

                test -f docker-compose.prod.yml || { echo "ERROR: docker-compose.prod.yml not found in workspace"; exit 1; }
                test -f nginx.conf || { echo "ERROR: nginx.conf not found in workspace"; exit 1; }

                echo "== Ensure remote app dir exists =="
                ssh -o StrictHostKeyChecking=no \\
                    -J "\${BASTION_USER}@\${BASTION_HOST}" \\
                    "\${APP_USER}@\${APP_HOST}" \\
                    "mkdir -p '\${APP_DIR}'"

                echo "== Copy deployment files to remote host =="
                scp -o StrictHostKeyChecking=no \\
                    -o ProxyJump="\${BASTION_USER}@\${BASTION_HOST}" \\
                    docker-compose.prod.yml \\
                    "\${APP_USER}@\${APP_HOST}:\${APP_DIR}/docker-compose.prod.yml"

                scp -o StrictHostKeyChecking=no \\
                    -o ProxyJump="\${BASTION_USER}@\${BASTION_HOST}" \\
                    nginx.conf \\
                    "\${APP_USER}@\${APP_HOST}:\${APP_DIR}/nginx.conf"

                echo "== Run remote deploy =="
                ssh -o StrictHostKeyChecking=no \\
                    -J "\${BASTION_USER}@\${BASTION_HOST}" \\
                    "\${APP_USER}@\${APP_HOST}" \\
                    "DOCKERHUB_USER='\${DOCKERHUB_USER}' DOCKERHUB_PASS='\${DOCKERHUB_PASS}' GIT_SHA='\${GIT_SHA}' APP_DIR='\${APP_DIR}' bash -s" <<'REMOTE'
      set -euo pipefail

      echo "== Remote preflight =="
      command -v docker >/dev/null 2>&1 || { echo "ERROR: docker not installed"; exit 1; }
      command -v aws >/dev/null 2>&1 || { echo "ERROR: aws cli not installed"; exit 1; }
      command -v jq >/dev/null 2>&1 || { echo "ERROR: jq not installed"; exit 1; }

      if docker compose version >/dev/null 2>&1; then
        COMPOSE="docker compose"
      elif docker-compose version >/dev/null 2>&1; then
        COMPOSE="docker-compose"
      else
        echo "ERROR: docker compose not found"
        exit 1
      fi

      mkdir -p "$APP_DIR"
      cd "$APP_DIR"

      test -f docker-compose.prod.yml || { echo "ERROR: docker-compose.prod.yml missing on remote host"; exit 1; }
      test -f nginx.conf || { echo "ERROR: nginx.conf missing on remote host"; exit 1; }

      echo "== Refreshing app.env from AWS Secrets Manager =="
      SECRET_JSON="\$(aws secretsmanager get-secret-value \
        --secret-id shop/prod/app \
        --query SecretString \
        --output text)"

      cat > app.env <<EOF
      JWT_SECRET=\$(echo "\$SECRET_JSON" | jq -r .JWT_SECRET)
      AUTH_DATABASE_URL=\$(echo "\$SECRET_JSON" | jq -r .AUTH_DATABASE_URL)
      PRODUCT_DATABASE_URL=\$(echo "\$SECRET_JSON" | jq -r .PRODUCT_DATABASE_URL)
      ORDER_DATABASE_URL=\$(echo "\$SECRET_JSON" | jq -r .ORDER_DATABASE_URL)
      RABBITMQ_URL=\$(echo "\$SECRET_JSON" | jq -r .RABBITMQ_URL)
      AWS_REGION=\$(echo "\$SECRET_JSON" | jq -r .AWS_REGION)
      SNS_TOPIC_ARN_NOTIFY=\$(echo "\$SECRET_JSON" | jq -r .SNS_TOPIC_ARN_NOTIFY)
      FRONTEND_BASE_URL=\$(echo "\$SECRET_JSON" | jq -r .FRONTEND_BASE_URL)
      S3_BUCKET=\$(echo "\$SECRET_JSON" | jq -r .S3_BUCKET)
      IMAGES_CDN_URL=\$(echo "\$SECRET_JSON" | jq -r .IMAGES_CDN_URL)
      VITE_AUTH_URL=\$(echo "\$SECRET_JSON" | jq -r .VITE_AUTH_URL)
      VITE_PRODUCT_URL=\$(echo "\$SECRET_JSON" | jq -r .VITE_PRODUCT_URL)
      VITE_ORDER_URL=\$(echo "\$SECRET_JSON" | jq -r .VITE_ORDER_URL)
      EOF

      chmod 600 app.env

        echo "== Writing images.env =="
      cat > images.env <<EOF
      AUTH_IMAGE=\${DOCKERHUB_USER}/auth:\${GIT_SHA}
      PRODUCT_IMAGE=\${DOCKERHUB_USER}/product:\${GIT_SHA}
      ORDER_IMAGE=\${DOCKERHUB_USER}/order:\${GIT_SHA}
      NOTIFY_IMAGE=\${DOCKERHUB_USER}/notify:\${GIT_SHA}
      WEB_IMAGE=\${DOCKERHUB_USER}/web:\${GIT_SHA}
      GATEWAY_IMAGE=\${DOCKERHUB_USER}/gateway:\${GIT_SHA}
      EOF

      chmod 600 images.env

      echo "== Docker login =="
      echo "\$DOCKERHUB_PASS" | docker login -u "\$DOCKERHUB_USER" --password-stdin

      echo "== Pulling release images =="
      \$COMPOSE -f docker-compose.prod.yml --env-file app.env --env-file images.env pull

      echo "== Starting/updating services =="
      \$COMPOSE -f docker-compose.prod.yml --env-file app.env --env-file images.env up -d --remove-orphans

      echo "== Current status =="
      \$COMPOSE -f docker-compose.prod.yml --env-file app.env --env-file images.env ps

      echo "== Docker logout =="
      docker logout || true
      REMOTE
              """
            }
          }
        }
      }
    }

    post {
      always {
        sh """#!/usr/bin/env bash
          set +e
          echo "================ POST / ALWAYS ================"

          if docker compose version >/dev/null 2>&1; then
            COMPOSE=(docker compose)
          else
            COMPOSE=(docker-compose)
          fi

          FILES=()
          ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n          ')}

          mkdir -p ci-artifacts

          echo "== Collecting logs =="
          "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" ps > ci-artifacts/compose-ps.txt 2>&1 || true
          "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" logs --no-color > ci-artifacts/compose-logs.txt 2>&1 || true
          "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" config > ci-artifacts/compose-config.rendered.yml 2>&1 || true

          echo "== Docker summary (host) =="
          docker ps -a > ci-artifacts/docker-ps-a.txt 2>&1 || true
          docker network ls > ci-artifacts/docker-networks.txt 2>&1 || true

          echo "== Cleaning up compose project =="
          "\${COMPOSE[@]}" -p "\$COMPOSE_PROJECT_NAME" "\${FILES[@]}" down -v --remove-orphans || true
        """

        archiveArtifacts artifacts: 'ci-artifacts/*', allowEmptyArchive: true
        cleanWs()
      }
    }
  }
}