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
            RUNTIME_SERVICE = "${config.runtimeService}"
            TEST_SERVICE = "${config.testService}"
            REPORT_GLOB = "${reportGlob}"
            PROJECT_NAME = "microshop-${config.serviceName}-${env.BRANCH_NAME ?: 'local'}-${env.BUILD_NUMBER}".replaceAll('[^A-Za-z0-9_.-]', '-')
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
                        rm -f "reports/${SERVICE_NAME}"/*.xml || true
                        docker version
                        docker compose version
                    '''
                }
            }

            stage('Build') {
                steps {
                    sh """
                        set -eux
                        docker compose -f docker-compose.yaml -f docker-compose.ci.yaml -p "${PROJECT_NAME}" build ${buildServices}
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
                        docker compose -f docker-compose.yaml -f docker-compose.ci.yaml -p "${PROJECT_NAME}" up -d ${startServices}
                    """
                }
            }

            stage('Test') {
                steps {
                    sh """
                        set -eux
                        docker compose -f docker-compose.yaml -f docker-compose.ci.yaml -p "${PROJECT_NAME}" run --rm ${TEST_SERVICE}
                    """
                }
            }
        }

        post {
            always {
                sh '''
                    set +e
                    docker compose -f docker-compose.yaml -f docker-compose.ci.yaml -p "${PROJECT_NAME}" logs --no-color > "compose-${SERVICE_NAME}.log" || true
                    docker compose -f docker-compose.yaml -f docker-compose.ci.yaml -p "${PROJECT_NAME}" down -v --remove-orphans || true
                '''
                archiveArtifacts artifacts: "compose-${SERVICE_NAME}.log, reports/${SERVICE_NAME}/*.xml", allowEmptyArchive: true
                junit testResults: "${REPORT_GLOB}", allowEmptyResults: true
                cleanWs()
            }
        }
    }
}