def targetEnv = System.getenv('TARGET_ENV') ?: 'dev'
def config = evaluate(readFileFromWorkspace("envs/${targetEnv}.groovy"))

def repoUrl = config.repoUrl
def credsId = config.credentialsId
def services = config.services ?: []

services.each { svc ->
    multibranchPipelineJob("${svc.name}-${targetEnv}-mb") {
        branchSources {
            git {
                id("${svc.name}-${targetEnv}-repo")
                remote(repoUrl)
                credentialsId(credsId)
            }
        }
        factory {
            workflowBranchProjectFactory {
                scriptPath(svc.scriptPath)
            }
        }
        triggers {
            periodic(1)
        }
    }
}