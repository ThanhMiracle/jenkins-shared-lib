def repoUrl = 'https://github.com/ThanhMiracle/micro-ecom.git'
def credsId = 'github-token'

def services = [
    [name: 'auth',    scriptPath: 'services/auth-service/Jenkinsfile'],
    [name: 'product', scriptPath: 'services/product-service/Jenkinsfile'],
    [name: 'order',   scriptPath: 'services/order-service/Jenkinsfile'],
    [name: 'payment', scriptPath: 'services/payment-service/Jenkinsfile'],
    [name: 'notify',  scriptPath: 'services/notification-service/Jenkinsfile']
]

services.each { svc ->
    pipelineJob(svc.name) {

        parameters {
            stringParam('BRANCH', 'main', 'Git branch to build')
        }
        
        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            url(repoUrl)
                            credentials(credsId)
                        }
                        branch("\${BRANCH}")
                    }
                }
                scriptPath(svc.scriptPath)
            }
        }
    }
}