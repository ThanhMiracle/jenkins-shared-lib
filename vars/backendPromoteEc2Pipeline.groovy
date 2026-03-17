import org.company.docker.DockerServices

def call(Map config = [:]) {
    def registry             = config.get('registry', '')
    def apiImageName         = config.get('apiImageName', 'simple-fullstack-api')
    def apiContext           = config.get('apiContext', './backend')
    def apiDockerfile        = config.get('apiDockerfile', 'Dockerfile')
    def apiTestCommand       = config.get('apiTestCommand', 'pytest -q -p no:cacheprovider tests')

    def composeDevFile       = config.get('composeDevFile', 'docker-compose.yml')
    def composeProdFile      = config.get('composeProdFile', 'docker-compose.prod.yml')
    def envFile              = config.get('envFile', '.env')

    def dockerRegistryCredId = config.get('dockerRegistryCredentialId', '')
    def sshCredentialId      = config.get('sshCredentialId', '')
    def ec2Host              = config.get('ec2Host', '')
    def ec2User              = config.get('ec2User', 'ec2-user')
    def deployPath           = config.get('deployPath', '/opt/simple-fullstack-app')

    def envCredentials       = config.get('envCredentials', [])
    def smokeUrl             = config.get('smokeUrl', '')
    def smokeApiUrl          = config.get('smokeApiUrl', '')

    

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        env.BRANCH_NAME_SAFE = env.BRANCH_NAME ?: sh(
                            script: "git rev-parse --abbrev-ref HEAD",
                            returnStdout: true
                        ).trim()

                        env.SHORT_COMMIT = sh(
                            script: "git rev-parse --short HEAD",
                            returnStdout: true
                        ).trim()

                        env.API_IMAGE = registry?.trim()
                            ? "${registry}/${apiImageName}:${env.SHORT_COMMIT}"
                            : "${apiImageName}:${env.SHORT_COMMIT}"
                    }
                }
            }

        stage('Test Backend') {
            when {
                branch 'dev'
            }
            steps {
                sh """
                    set -e
                    export COMPOSE_PROJECT_NAME=myappci

                    docker compose -f ${composeDevFile} up -d db minio
                    sleep 15

                    docker build --target test \
                        -t ${apiImageName}-test:${env.SHORT_COMMIT} \
                        -f ${apiContext}/${apiDockerfile} \
                        ${apiContext}

                    docker run --rm \
                        --network myappci_default \
                        ${apiImageName}-test:${env.SHORT_COMMIT}
                """
            }
            post {
                always {
                    sh """
                        export COMPOSE_PROJECT_NAME=myappci
                        docker compose -f ${composeDevFile} down -v
                    """
                }
            }
        }

        stage('Build Backend Image') {
            when {
                branch 'dev'
            }
            steps {
                script {
                    def dockerBuilder = new DockerServices(this)
                    dockerBuilder.build(
                        apiImage: env.API_IMAGE,
                        apiContext: apiContext,
                        apiDockerfile: apiDockerfile
                    )
                            }
                        }
                    }
                }
            }
        }   

            // stage('Push Backend Image') {
            //     when {
            //         branch 'dev'
            //     }
            //     steps {
            //         withCredentials([usernamePassword(
            //             credentialsId: dockerRegistryCredId,
            //             usernameVariable: 'DOCKER_USERNAME',
            //             passwordVariable: 'DOCKER_PASSWORD'
            //         )]) {
            //             sh """
            //                 set -e
            //                 echo "\$DOCKER_PASSWORD" | docker login -u "\$DOCKER_USERNAME" --password-stdin
            //                 docker push ${env.API_IMAGE}
            //                 docker logout
            //             """
            //         }
            //     }
            // }

            stage('Prepare Deploy Env') {
                when {
                    branch 'main'
                }
                steps {
                    script {
                        def bindings = envCredentials.collect { id ->
                            string(credentialsId: id, variable: id)
                        }

                        withCredentials(bindings) {
                            def lines = []
                            envCredentials.each { id ->
                                lines << "${id}=${env[id]}"
                            }
                            lines << "API_IMAGE=${env.API_IMAGE}"

                            writeFile file: envFile, text: lines.join('\n') + '\n'
                            sh "chmod 600 ${envFile}"
                        }
                    }
                }
            }

            stage('Deploy to EC2') {
                when {
                    branch 'main'
                }
                steps {
                    sshagent(credentials: [sshCredentialId]) {
                        sh """
                            set -e

                            ssh -o StrictHostKeyChecking=no ${ec2User}@${ec2Host} 'mkdir -p ${deployPath}/nginx'

                            scp -o StrictHostKeyChecking=no ${composeProdFile} ${ec2User}@${ec2Host}:${deployPath}/docker-compose.prod.yml
                            scp -o StrictHostKeyChecking=no ${envFile} ${ec2User}@${ec2Host}:${deployPath}/.env
                            scp -o StrictHostKeyChecking=no nginx/nginx.conf ${ec2User}@${ec2Host}:${deployPath}/nginx/nginx.conf

                            ssh -o StrictHostKeyChecking=no ${ec2User}@${ec2Host} '
                                set -e
                                cd ${deployPath}
                                docker compose --env-file .env -f docker-compose.prod.yml pull
                                docker compose --env-file .env -f docker-compose.prod.yml up -d --remove-orphans
                                docker compose --env-file .env -f docker-compose.prod.yml ps
                            '
                        """
                    }
                }
            }

            stage('Smoke Test') {
                when {
                    branch 'main'
                }
                steps {
                    script {
                        if (smokeUrl?.trim()) {
                            sh "curl -I --retry 12 --retry-delay 5 --retry-connrefused ${smokeUrl}"
                        }
                        if (smokeApiUrl?.trim()) {
                            sh "curl --retry 12 --retry-delay 5 --retry-connrefused ${smokeApiUrl}"
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    if (env.BRANCH_NAME_SAFE == 'dev') {
                        sh """
                            docker compose -f ${composeDevFile} down -v || true
                        """
                    }
                }
            }
        }
    }
}