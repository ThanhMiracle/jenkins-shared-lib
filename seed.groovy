services.each { svc ->
    pipelineJob(svc.name) {
        description("Pipeline for ${svc.name}")

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