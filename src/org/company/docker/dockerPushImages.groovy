def call(Map config = [:]) {
    def dockerRegistryCredentialId = config.get('dockerRegistryCredentialId')
    def frontendImage = config.get('frontendImage')
    def apiImage = config.get('apiImage')

    withCredentials([usernamePassword(
        credentialsId: dockerRegistryCredentialId,
        usernameVariable: 'DOCKER_USERNAME',
        passwordVariable: 'DOCKER_PASSWORD'
    )]) {
        sh """
            set -e
            echo "${'$'}DOCKER_PASSWORD" | docker login -u "${'$'}DOCKER_USERNAME" --password-stdin
            docker push ${frontendImage}
            docker push ${apiImage}
            docker logout
        """
    }
}