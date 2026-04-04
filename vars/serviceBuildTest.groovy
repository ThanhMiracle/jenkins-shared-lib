def call(Map config = [:]) {
    if (!config.serviceName) {
        error("serviceName is required")
    }
    if (!config.runtimeService) {
        error("runtimeService is required")
    }
    if (!config.testService) {
        error("testService is required")
    }

    def deps = config.startDeps ?: []
    def buildServices = ([config.runtimeService, config.testService] + deps).unique().join(' ')
    def startServices = deps.join(' ')
    def reportGlob = config.reportGlob ?: "reports/${config.serviceName}/junit.xml"

    // Defaults you can override from each Jenkinsfile
    def serviceDir = config.serviceDir ?: "services/${config.serviceName}-service"
    def sonarEnabled = config.get('sonarEnabled', true)
    def trivyEnabled = config.get('trivyEnabled', true)
    def sonarServer = config.sonarServer ?: 'sonarqube'
    def sonarScannerTool = config.sonarScannerTool ?: 'SonarScanner'
    def sonarProjectKey = config.sonarProjectKey ?: "micro-ecom-${config.serviceName}"
    def sonarProjectName = config.sonarProjectName ?: "micro-ecom-${config.serviceName}"
    def sonarSources = config.sonarSources ?: '.'
    def sonarExclusions = config.sonarExclusions ?: 'node_modules/**,coverage/**,dist/**,build/**,target/**'
    def sonarExtraArgs = config.sonarExtraArgs ?: ''
    def trivySeverity = config.trivySeverity ?: 'HIGH,CRITICAL'
    def trivyFsExitCode = config.trivyFsExitCode ?: '1'
    def trivyImageExitCode = config.trivyImageExitCode ?: '1'

    pipeline {
        agent any

        options {
            timestamps()
            ansiColor('xterm')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        environment {
            SERVICE_NAME = "${config.serviceName}"
            SERVICE_DIR = "${serviceDir}"
            RUNTIME_SERVICE = "${config.runtimeService}"
            TEST_SERVICE = "${config.testService}"
            REPORT_GLOB = "${reportGlob}"
            PROJECT_NAME = "microshop-${config.serviceName}-${env.BRANCH_NAME ?: 'local'}-${env.BUILD_NUMBER}".replaceAll('[^A-Za-z0-9_.-]', '-')

            SONAR_ENABLED = "${sonarEnabled}"
            TRIVY_ENABLED = "${trivyEnabled}"
            SONAR_SERVER = "${sonarServer}"
            SONAR_SCANNER_TOOL = "${sonarScannerTool}"
            SONAR_PROJECT_KEY = "${sonarProjectKey}"
            SONAR_PROJECT_NAME = "${sonarProjectName}"
            SONAR_SOURCES = "${sonarSources}"
            SONAR_EXCLUSIONS = "${sonarExclusions}"
            SONAR_EXTRA_ARGS = "${sonarExtraArgs}"

            TRIVY_SEVERITY = "${trivySeverity}"
            TRIVY_FS_EXIT_CODE = "${trivyFsExitCode}"
            TRIVY_IMAGE_EXIT_CODE = "${trivyImageExitCode}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Prepare') {
                steps {
                    sh '''
                        set -eux
                        mkdir -p "reports/${SERVICE_NAME}"
                        mkdir -p "reports/${SERVICE_NAME}/trivy"
                        rm -f "reports/${SERVICE_NAME}"/*.xml || true
                        rm -f "reports/${SERVICE_NAME}/trivy"/* || true
                        docker version
                        docker compose version
                    '''
                }
            }

            stage('Build') {
                steps {
                    sh """
                        set -eux
                        docker compose -f docker-compose.yml -f docker-compose.ci.yml -p "${PROJECT_NAME}" build ${buildServices}
                    """
                }
            }

            stage('Start Dependencies') {
                when {
                    expression { return startServices?.trim() }
                }
                steps {
                    sh """
                        set -eux
                        docker compose -f docker-compose.yml -f docker-compose.ci.yml -p "${PROJECT_NAME}" up -d ${startServices}
                    """
                }
            }

            stage('Test') {
                steps {
                    sh """
                        set -eux
                        docker compose -f docker-compose.yml -f docker-compose.ci.yml -p "${PROJECT_NAME}" run --rm ${TEST_SERVICE}
                    """
                }
            }

            stage('SonarQube Analysis') {
                when {
                    expression { return env.SONAR_ENABLED == 'true' }
                }
                steps {
                    script {
                        def scannerHome = tool env.SONAR_SCANNER_TOOL
                        withSonarQubeEnv(env.SONAR_SERVER) {
                            sh """
                                set -eux
                                cd "${SERVICE_DIR}"
                                "${scannerHome}/bin/sonar-scanner" \
                                  -Dsonar.projectKey="${SONAR_PROJECT_KEY}" \
                                  -Dsonar.projectName="${SONAR_PROJECT_NAME}" \
                                  -Dsonar.sources="${SONAR_SOURCES}" \
                                  -Dsonar.exclusions="${SONAR_EXCLUSIONS}" \
                                  ${SONAR_EXTRA_ARGS}
                            """
                        }
                    }
                }
            }

            stage('Quality Gate') {
                when {
                    expression { return env.SONAR_ENABLED == 'true' }
                }
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Trivy Filesystem Scan') {
                when {
                    expression { return env.TRIVY_ENABLED == 'true' }
                }
                steps {
                    sh """
                        set -eux
                        trivy fs \
                          --scanners vuln,secret,misconfig \
                          --severity "${TRIVY_SEVERITY}" \
                          --exit-code "${TRIVY_FS_EXIT_CODE}" \
                          --format table \
                          --output "reports/${SERVICE_NAME}/trivy/fs-scan.txt" \
                          "${SERVICE_DIR}"
                    """
                }
            }

            stage('Trivy Image Scan') {
                when {
                    expression { return env.TRIVY_ENABLED == 'true' }
                }
                steps {
                    sh """
                        set -eux
                        mkdir -p "reports/${SERVICE_NAME}/trivy"
                        mkdir -p .trivycache

                        IMAGE_NAME="micro-ecom/${SERVICE_NAME}:ci"

                        docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -v "\$PWD:/workspace" \
                        -v "\$PWD/.trivycache:/root/.cache/trivy" \
                        aquasec/trivy:latest image \
                        --severity "${TRIVY_SEVERITY}" \
                        --exit-code "${TRIVY_IMAGE_EXIT_CODE}" \
                        --format table \
                        --output "/workspace/reports/${SERVICE_NAME}/trivy/image-scan.txt" \
                        "${IMAGE_NAME}"
                    """
                }
            }
        }

        post {
            always {
                sh '''
                    set +e
                    docker compose -f docker-compose.yml -f docker-compose.ci.yml -p "${PROJECT_NAME}" logs --no-color > "compose-${SERVICE_NAME}.log" || true
                    docker compose -f docker-compose.yml -f docker-compose.ci.yml -p "${PROJECT_NAME}" images > "images-${SERVICE_NAME}.log" || true
                    docker compose -f docker-compose.yml -f docker-compose.ci.yml -p "${PROJECT_NAME}" down -v --remove-orphans || true
                '''
                archiveArtifacts artifacts: "compose-${SERVICE_NAME}.log, images-${SERVICE_NAME}.log, reports/${SERVICE_NAME}/*.xml, reports/${SERVICE_NAME}/trivy/*", allowEmptyArchive: true
                junit testResults: "${REPORT_GLOB}", allowEmptyResults: true
                cleanWs()
            }
        }
    }
}