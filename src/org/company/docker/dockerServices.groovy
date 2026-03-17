package org.company.docker

class DockerBuildOnly implements Serializable {
    def steps

    DockerBuildOnly(steps) {
        this.steps = steps
    }

    def build(Map config = [:]) {
        def frontendImage = config.get('frontendImage', '')
        def apiImage = config.get('apiImage', '')
        def frontendContext = config.get('frontendContext', './frontend')
        def apiContext = config.get('apiContext', './api')
        def frontendDockerfile = config.get('frontendDockerfile', 'Dockerfile')
        def apiDockerfile = config.get('apiDockerfile', 'Dockerfile')

        if (frontendImage?.trim()) {
            steps.sh """
                set -e
                docker build -t ${frontendImage} -f ${frontendContext}/${frontendDockerfile} ${frontendContext}
            """
        }

        if (apiImage?.trim()) {
            steps.sh """
                set -e
                docker build -t ${apiImage} -f ${apiContext}/${apiDockerfile} ${apiContext}
            """
        }
    }
}