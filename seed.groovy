def config = readYaml file: 'envs/master.yml'

def repoUrl = config.repoUrl
def credsId = config.credentialsId

config.services.each { svc ->
    multibranchPipelineJob("${svc.name}-mb") {
        branchSources {
            git {
                id("${svc.name}-repo")
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