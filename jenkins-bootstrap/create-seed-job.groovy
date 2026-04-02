import jenkins.model.*
import hudson.model.*
import javaposse.jobdsl.plugin.ExecuteDslScripts
import javaposse.jobdsl.plugin.RemovedJobAction
import javaposse.jobdsl.plugin.RemovedViewAction
import javaposse.jobdsl.plugin.LookupStrategy
import hudson.plugins.git.*

def jenkins = Jenkins.instance

if (jenkins.getItem('seed-job') == null) {
    println('Creating seed-job...')

    def job = jenkins.createProject(FreeStyleProject, 'seed-job')
    job.setDescription('Bootstrap Job DSL seed job for microshop')

    def scm = new GitSCM(
        GitSCM.createRepoList(
            'https://github.com/ThanhMiracle/jenkins-shared-lib.git',
            'github-pat'
        ),
        Collections.emptyList(),
        Collections.emptyList(),
        false,
        Collections.emptyList(),
        null,
        null,
        Collections.emptyList()
    )

    job.setScm(scm)

    def dsl = new ExecuteDslScripts()
    dsl.setTargets('seed.groovy')
    dsl.setRemovedJobAction(RemovedJobAction.DELETE)
    dsl.setRemovedViewAction(RemovedViewAction.DELETE)
    dsl.setLookupStrategy(LookupStrategy.JENKINS_ROOT)
    dsl.setIgnoreExisting(false)

    job.buildersList.add(dsl)
    job.save()
    job.scheduleBuild2(0)

    println('seed-job created and triggered')
} else {
    println('seed-job already exists')
}