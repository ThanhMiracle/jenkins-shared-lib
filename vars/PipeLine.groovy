import org.ci.PipelineConfig
import org.ci.ComposeHelper
import org.ci.SonarHelper
import org.ci.TrivyHelper
import org.ci.ReportHelper

def call(Map rawConfig = [:]) {
    def config = PipelineConfig.from(rawConfig)

    pipeline {
        agent any

        options {
            timestamps()
            ansiColor('xterm')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        environment {
            SERVICE_NAME = config.serviceName
            SERVICE_DIR = config.serviceDir
            RUNTIME_SERVICE = config.runtimeService
            TEST_SERVICE = config.testService
            REPORT_GLOB = config.reportGlob
            PROJECT_NAME = config.projectName

            SONAR_ENABLED = config.sonarEnabled.toString()
            TRIVY_ENABLED = config.trivyEnabled.toString()
            SONAR_SERVER = config.sonarServer
            SONAR_SCANNER_TOOL = config.sonarScannerTool
            SONAR_PROJECT_KEY = config.sonarProjectKey
            SONAR_PROJECT_NAME = config.sonarProjectName
            SONAR_SOURCES = config.sonarSources
            SONAR_EXCLUSIONS = config.sonarExclusions
            SONAR_EXTRA_ARGS = config.sonarExtraArgs

            TRIVY_SEVERITY = config.trivySeverity
            TRIVY_FS_EXIT_CODE = config.trivyFsExitCode
            TRIVY_IMAGE_EXIT_CODE = config.trivyImageExitCode
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Prepare') {
                steps {
                    script {
                        ComposeHelper.prepare(this, config)
                    }
                }
            }

            stage('Build') {
                steps {
                    script {
                        ComposeHelper.build(this, config)
                    }
                }
            }

            stage('Start Dependencies') {
                when {
                    expression { config.startServices?.trim() }
                }
                steps {
                    script {
                        ComposeHelper.upDeps(this, config)
                    }
                }
            }

            stage('Test') {
                steps {
                    script {
                        ComposeHelper.runTests(this, config)
                    }
                }
            }

            stage('SonarQube Analysis') {
                when {
                    expression { env.SONAR_ENABLED == 'true' }
                }
                steps {
                    script {
                        SonarHelper.scan(this, config)
                    }
                }
            }

            stage('Quality Gate') {
                when {
                    expression { env.SONAR_ENABLED == 'true' }
                }
                steps {
                    script {
                        SonarHelper.qualityGate(this)
                    }
                }
            }

            stage('Trivy Filesystem Scan') {
                when {
                    expression { env.TRIVY_ENABLED == 'true' }
                }
                steps {
                    script {
                        TrivyHelper.fsScan(this, config)
                    }
                }
            }

            stage('Trivy Image Scan') {
                when {
                    expression { env.TRIVY_ENABLED == 'true' }
                }
                steps {
                    script {
                        TrivyHelper.imageScan(this, config)
                    }
                }
            }
        }

        post {
            always {
                script {
                    ReportHelper.collect(this, config)
                }
            }
        }
    }
}