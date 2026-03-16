def call(Map config = [:]) {
    def composeFile = config.get('composeFile', 'docker-compose.yml')
    def envFile = config.get('envFile', '.env')
    def ec2Host = config.get('ec2Host')
    def ec2User = config.get('ec2User', 'ec2-user')
    def sshCredentialId = config.get('sshCredentialId')
    def deployPath = config.get('deployPath', '/opt/app')

    sshagent(credentials: [sshCredentialId]) {
        sh """
            set -e

            ssh -o StrictHostKeyChecking=no ${ec2User}@${ec2Host} 'mkdir -p ${deployPath}/nginx'
            scp -o StrictHostKeyChecking=no ${composeFile} ${ec2User}@${ec2Host}:${deployPath}/docker-compose.yml
            scp -o StrictHostKeyChecking=no ${envFile} ${ec2User}@${ec2Host}:${deployPath}/.env
            scp -o StrictHostKeyChecking=no nginx/nginx.conf ${ec2User}@${ec2Host}:${deployPath}/nginx/nginx.conf

            ssh -o StrictHostKeyChecking=no ${ec2User}@${ec2Host} '
                set -e
                cd ${deployPath}
                docker compose --env-file .env -f docker-compose.yml pull
                docker compose --env-file .env -f docker-compose.yml up -d --remove-orphans
                docker compose --env-file .env -f docker-compose.yml ps
            '
        """
    }
}