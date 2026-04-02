folder('microshop') {
    description('Microshop service CI pipelines')
}
def repoUrl = 'https://github.com/ThanhMiracle/micro-ecom'
def credsId = 'github-pat'

def services = [
    [name: 'auth',    scriptPath: 'services/auth-service/Jenkinsfile'],
    [name: 'product', scriptPath: 'services/product-service/Jenkinsfile'],
    [name: 'order',   scriptPath: 'services/order-service/Jenkinsfile'],
    [name: 'payment', scriptPath: 'services/payment-service/Jenkinsfile'],
    [name: 'notify',  scriptPath: 'services/notification-service/Jenkinsfile']
]

services.each { svc ->
    multibranchPipelineJob("microshop/${svc.name}") {
        description("Build and test ${svc.name} service across all branches")

        branchSources {
            git {
                id("microshop-${svc.name}")
                remote(repoUrl)
                credentialsId(credsId)
                includes('*')
                excludes('')
            }
        }

        factory {
            workflowBranchProjectFactory {
                scriptPath(svc.scriptPath)
            }
        }

        orphanedItemStrategy {
            discardOldItems {
                numToKeep(20)
            }
        }

        triggers {
            periodicFolderTrigger {
                interval('1h')
            }
        }
    }
}