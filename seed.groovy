def targetEnv = System.getenv('TARGET_ENV') ?: 'dev'
def config = evaluate(readFileFromWorkspace("envs/${targetEnv}.groovy"))

def appName = config.appName
def repoUrl = config.repoUrl
def credsId = config.credentialsId
def scriptPath = config.scriptPath ?: 'Jenkinsfile'

if (!appName || !repoUrl || !credsId) {
    throw new IllegalArgumentException('appName, repoUrl and credentialsId are required')
}

multibranchPipelineJob("${appName}-${targetEnv}-mb") {
    description("Full-stack frontend/backend pipeline for ${targetEnv}")

    branchSources {
        git {
            id("${appName}-${targetEnv}-repo")
            remote(repoUrl)
            credentialsId(credsId)
        }
    }

    factory {
        workflowBranchProjectFactory {
            scriptPath(scriptPath)
        }
    }

    triggers {
        periodicFolderTrigger {
            interval('5m')
        }
    }
}
