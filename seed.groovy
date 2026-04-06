import org.yaml.snakeyaml.Yaml

def yaml = new Yaml()
def config = yaml.load(readFileFromWorkspace('jenkins/services.yml'))

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