import groovy.json.JsonOutput
import org.ci.AsgPipelineConfig

def call(Map rawConfig = [:]) {
    def config = AsgPipelineConfig.from(rawConfig)

    pipeline {
        agent any

        options {
            timestamps()
            ansiColor('xterm')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        environment {
            DOCKER_BUILDKIT = '1'
            AWS_DEFAULT_REGION = "${config.awsRegion}"
            RELEASE_TAG = "${env.GIT_COMMIT ?: 'pending'}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        env.RELEASE_TAG = sh(
                            script: 'git rev-parse --short=12 HEAD',
                            returnStdout: true
                        ).trim()
                    }
                }
            }

            stage('Preflight') {
                steps {
                    sh """
                        set -eu
                        test -f '${config.composeFile}'
                        test -f '${config.frontendContext}/Dockerfile'
                        test -f '${config.backendContext}/Dockerfile'
                        test -d '${config.nginxDir}'
                        docker version
                        docker compose version
                        aws --version
                        docker compose -f '${config.composeFile}' config --quiet
                    """
                }
            }

            stage('Test') {
                steps {
                    sh """
                        set -eu
                        ${config.testCommand}
                    """
                }
                post {
                    always {
                        sh """
                            docker compose -f docker-compose.yml \
                              down -v --remove-orphans || true
                        """
                    }
                }
            }

            stage('Build images') {
                steps {
                    sh """
                        set -eux
                        docker build --pull \
                          -t '${config.frontendImage}:${env.RELEASE_TAG}' \
                          -t '${config.frontendImage}:latest' \
                          '${config.frontendContext}'
                        docker build --pull \
                          -t '${config.backendImage}:${env.RELEASE_TAG}' \
                          -t '${config.backendImage}:latest' \
                          '${config.backendContext}'
                    """
                }
            }

            stage('Security scan') {
                when {
                    expression { config.trivyEnabled }
                }
                steps {
                    sh """
                        set -eux
                        trivy image --exit-code 1 --severity HIGH,CRITICAL \
                          '${config.frontendImage}:${env.RELEASE_TAG}'
                        trivy image --exit-code 1 --severity HIGH,CRITICAL \
                          '${config.backendImage}:${env.RELEASE_TAG}'
                    """
                }
            }

            stage('Push images') {
                when {
                    branch config.deployBranch
                }
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: config.dockerCredentialsId,
                        usernameVariable: 'DOCKER_USERNAME',
                        passwordVariable: 'DOCKER_PASSWORD'
                    )]) {
                        sh """
                            set -eux
                            set +x
                            printf '%s' "\${DOCKER_PASSWORD}" | \
                              docker login '${config.dockerRegistry}' \
                                --username "\${DOCKER_USERNAME}" --password-stdin
                            set -x
                            docker push '${config.frontendImage}:${env.RELEASE_TAG}'
                            docker push '${config.frontendImage}:latest'
                            docker push '${config.backendImage}:${env.RELEASE_TAG}'
                            docker push '${config.backendImage}:latest'
                            docker logout '${config.dockerRegistry}'
                        """
                    }
                }
            }

            stage('Create deployment bundle') {
                when {
                    branch config.deployBranch
                }
                steps {
                    script {
                        def releaseCompose = [
                            services: [
                                (config.frontendService): [image: "${config.frontendImage}:${env.RELEASE_TAG}"],
                                (config.backendService): [image: "${config.backendImage}:${env.RELEASE_TAG}"]
                            ]
                        ]
                        writeFile(
                            file: 'docker-compose.release.yml',
                            text: groovy.json.JsonOutput.prettyPrint(JsonOutput.toJson(releaseCompose))
                        )
                    }
                    sh """
                        set -eux
                        rm -rf .deploy-bundle
                        mkdir -p '.deploy-bundle/${config.nginxDir}'
                        cp '${config.composeFile}' .deploy-bundle/docker-compose.yml
                        cp docker-compose.release.yml .deploy-bundle/
                        cp -R '${config.nginxDir}/.' '.deploy-bundle/${config.nginxDir}/'
                        tar -C .deploy-bundle -czf "release-${env.RELEASE_TAG}.tgz" .
                    """
                }
            }

            stage('Deploy ASG') {
                when {
                    branch config.deployBranch
                }
                options {
                    timeout(time: 45, unit: 'MINUTES')
                }
                steps {
                    script {
                        def deploy = {
                            deployToAsg(this, config)
                        }
                        if (config.awsCredentialsId) {
                            withCredentials([[
                                $class: 'AmazonWebServicesCredentialsBinding',
                                credentialsId: config.awsCredentialsId,
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                            ]]) {
                                deploy()
                            }
                        } else {
                            deploy()
                        }
                    }
                }
            }
        }

        post {
            always {
                archiveArtifacts artifacts: 'release-*.tgz', allowEmptyArchive: true
                sh 'docker logout >/dev/null 2>&1 || true'
            }
        }
    }
}

private void deployToAsg(script, config) {
    def artifactUri = "s3://${config.artifactBucket}/releases/release-${script.env.RELEASE_TAG}.tgz"
    script.sh """
        set -eux
        aws s3 cp 'release-${script.env.RELEASE_TAG}.tgz' '${artifactUri}' \
          --region '${config.awsRegion}'
    """

    def userData = script.libraryResource('org/ci/ec2-asg-bootstrap.sh')
        .replace('__AWS_REGION__', config.awsRegion)
        .replace('__ARTIFACT_URI__', artifactUri)
        .replace('__ENV_PARAMETER__', config.environmentParameter)
        .replace('__RELEASE__', script.env.RELEASE_TAG)
    script.writeFile(file: '.ec2-user-data.sh', text: userData)

    script.sh """
        set -eux
        USER_DATA=\$(base64 -w 0 .ec2-user-data.sh)
        SOURCE_VERSION=\$(aws ec2 describe-launch-templates \
          --launch-template-ids '${config.launchTemplateId}' \
          --query 'LaunchTemplates[0].DefaultVersionNumber' \
          --output text)
        printf '{"UserData":"%s"}' "\${USER_DATA}" > .launch-template-data.json
        NEW_VERSION=\$(aws ec2 create-launch-template-version \
          --launch-template-id '${config.launchTemplateId}' \
          --source-version "\${SOURCE_VERSION}" \
          --version-description 'release-${script.env.RELEASE_TAG}' \
          --launch-template-data file://.launch-template-data.json \
          --query 'LaunchTemplateVersion.VersionNumber' \
          --output text)
        aws ec2 modify-launch-template \
          --launch-template-id '${config.launchTemplateId}' \
          --default-version "\${NEW_VERSION}"
        aws autoscaling update-auto-scaling-group \
          --auto-scaling-group-name '${config.autoScalingGroup}' \
          --launch-template "LaunchTemplateId=${config.launchTemplateId},Version=\${NEW_VERSION}"
        REFRESH_ID=\$(aws autoscaling start-instance-refresh \
          --auto-scaling-group-name '${config.autoScalingGroup}' \
          --preferences '{"MinHealthyPercentage":90,"InstanceWarmup":180,"AutoRollback":true}' \
          --query 'InstanceRefreshId' \
          --output text)
        echo "Instance refresh: \${REFRESH_ID}"

        while true; do
          STATUS=\$(aws autoscaling describe-instance-refreshes \
            --auto-scaling-group-name '${config.autoScalingGroup}' \
            --instance-refresh-ids "\${REFRESH_ID}" \
            --query 'InstanceRefreshes[0].Status' \
            --output text)
          echo "ASG refresh status: \${STATUS}"
          case "\${STATUS}" in
            Successful) break ;;
            Failed|Cancelled|RollbackFailed|RollbackSuccessful)
              aws autoscaling describe-instance-refreshes \
                --auto-scaling-group-name '${config.autoScalingGroup}' \
                --instance-refresh-ids "\${REFRESH_ID}"
              exit 1
              ;;
          esac
          sleep 30
        done
    """
}
