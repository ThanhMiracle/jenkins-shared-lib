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
            echo "COMPOSE_PROJECT_NAME=${env.COMPOSE_PROJECT_NAME}"
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
                ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n      ')}

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
            // Safe branch detection in Groovy: prefer BRANCH_NAME, else strip origin/ from GIT_BRANCH
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
                  COMPOSE="docker compose"
                else
                  COMPOSE="docker-compose"
                fi
                C="\$COMPOSE -p '${env.COMPOSE_PROJECT_NAME}' ${composeFiles.collect { "-f '${it}'" }.join(' ')}"

                mkdir -p ci-artifacts

                echo "== Starting infra: ${infraServices.join(' ')} =="
                \$C up -d ${infraServices.join(' ')}

                echo "== Waiting for healthchecks (best-effort) =="
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

                ${testServices.collect { "run_test ${it}" }.join('\n                ')}
              """
            } else {
              // Parallel mode: each test service runs in its own branch
              def branches = [:]
              for (String svc : testServices) {
                branches[svc] = {
                  sh """#!/usr/bin/env bash
                    set -euxo pipefail
                    echo "---------- RUN TEST (parallel): ${svc} ----------"

                    if docker compose version >/dev/null 2>&1; then
                      COMPOSE="docker compose"
                    else
                      COMPOSE="docker-compose"
                    fi
                    C="\$COMPOSE -p '${env.COMPOSE_PROJECT_NAME}' ${composeFiles.collect { "-f '${it}'" }.join(' ')}

                    mkdir -p ci-artifacts
                    \$C run --rm '${svc}' 2>&1 | tee "ci-artifacts/${svc}.out.log"
                  """
                }
              }

              // Start infra once before parallel run
              sh """#!/usr/bin/env bash
                set -euxo pipefail
                echo "== Starting infra for parallel tests: ${infraServices.join(' ')} =="

                if docker compose version >/dev/null 2>&1; then
                  COMPOSE="docker compose"
                else
                  COMPOSE="docker-compose"
                fi
                C="\$COMPOSE -p '${env.COMPOSE_PROJECT_NAME}' ${composeFiles.collect { "-f '${it}'" }.join(' ')}

                mkdir -p ci-artifacts
                \$C up -d ${infraServices.join(' ')}
                \$C up -d --wait ${infraServices.join(' ')} || true
                \$C ps || true
              """

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
                COMPOSE="docker compose"
              else
                COMPOSE="docker-compose"
              fi
              C="\$COMPOSE -p '${env.COMPOSE_PROJECT_NAME}' ${composeFiles.collect { "-f '${it}'" }.join(' ')}

              mkdir -p ci-artifacts

              GIT_SHA="\$(git rev-parse --short=12 HEAD)"
              echo "GIT_SHA=\$GIT_SHA" | tee ci-artifacts/git-sha.txt

              echo "\$DOCKERHUB_PASS" | docker login -u "\$DOCKERHUB_USER" --password-stdin

              echo "== Images from compose =="
              \$C config --images | sort -u | tee ci-artifacts/compose-images.txt

              while read -r img; do
                [ -z "\$img" ] && continue

                # img could be "thanh2909/auth-service:v0.3" or "auth-service:v0.3" etc.
                # Strip tag
                base="\${img%:*}"
                [ "\$base" = "\$img" ] && base="\$img"

                # repo name is last path segment
                repo="\${base##*/}"
                dest_base="\${DOCKERHUB_USER}/\${repo}"

                dest_sha="\${dest_base}:\${GIT_SHA}"
                dest_branch_latest="\${dest_base}:${pushBranch}-latest"

                echo "== Tagging \$img -> \$dest_sha and \$dest_branch_latest =="
                docker tag "\$img" "\$dest_sha"
                docker tag "\$img" "\$dest_branch_latest"

                docker push "\$dest_sha"
                docker push "\$dest_branch_latest"
              done < <(\$C config --images | sort -u)

              docker logout || true
            """
          }
        }
      }

      stage('Deploy') {
        when {
          expression {
            def b = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '')
            b = b.replaceFirst(/^origin\\//, '').replaceFirst(/^refs\\/heads\\//, '')
            return b == deployBranch
          }
        }
        steps {
          sh """#!/usr/bin/env bash
            set -euo pipefail
            echo "================ DEPLOY (only: ${deployBranch.toUpperCase()}) ================"
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

            if docker compose version >/dev/null 2>&1; then
                COMPOSE=(docker compose)
            else
                COMPOSE=(docker-compose)
            fi

            FILES=()
            ${composeFiles.collect { "FILES+=(-f ${it})" }.join('\n      ')}

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