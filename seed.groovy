def appName = 'docker'
def repoUrl = 'https://github.com/ThanhMiracle/docker.git'
def credentialsId = 'github-pat'

multibranchPipelineJob("${appName}-mb") {
    description('CI for every branch; build, push and deploy main to EC2 ASG')

    branchSources {
        git {
            id("${appName}-repo")
            remote(repoUrl)
            credentialsId(credentialsId)
        }
    }

    factory {
        workflowBranchProjectFactory {
            scriptPath('Jenkinsfile')
        }
    }

    triggers {
        periodicFolderTrigger {
            interval('5m')
        }
    }

    orphanedItemStrategy {
        discardOldItems {
            numToKeep(20)
        }
    }
}
