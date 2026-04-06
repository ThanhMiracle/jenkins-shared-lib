package org.ci

class PipelineConfig implements Serializable {
    String serviceName
    String runtimeService
    String testService
    List startDeps = []

    String reportGlob
    String serviceDir
    boolean sonarEnabled
    boolean trivyEnabled
    String sonarServer
    String sonarScannerTool
    String sonarProjectKey
    String sonarProjectName
    String sonarSources
    String sonarExclusions
    String sonarExtraArgs
    String trivySeverity
    String trivyFsExitCode
    String trivyImageExitCode
    String buildServices
    String startServices
    String projectName

    static PipelineConfig from(Map config) {
        if (!config.serviceName) throw new IllegalArgumentException("serviceName is required")
        if (!config.runtimeService) throw new IllegalArgumentException("runtimeService is required")
        if (!config.testService) throw new IllegalArgumentException("testService is required")

        def c = new PipelineConfig()
        c.serviceName = config.serviceName
        c.runtimeService = config.runtimeService
        c.testService = config.testService
        c.startDeps = config.startDeps ?: []

        c.reportGlob = config.reportGlob ?: "reports/${c.serviceName}/junit.xml"
        c.serviceDir = config.serviceDir ?: "services/${c.serviceName}-service"
        c.sonarEnabled = config.get('sonarEnabled', true)
        c.trivyEnabled = config.get('trivyEnabled', true)
        c.sonarServer = config.sonarServer ?: 'sonarqube'
        c.sonarScannerTool = config.sonarScannerTool ?: 'SonarScanner'
        c.sonarProjectKey = config.sonarProjectKey ?: "micro-ecom-${c.serviceName}"
        c.sonarProjectName = config.sonarProjectName ?: "micro-ecom-${c.serviceName}"
        c.sonarSources = config.sonarSources ?: '.'
        c.sonarExclusions = config.sonarExclusions ?: 'node_modules/**,coverage/**,dist/**,build/**,target/**'
        c.sonarExtraArgs = config.sonarExtraArgs ?: ''
        c.trivySeverity = config.trivySeverity ?: 'HIGH,CRITICAL'
        c.trivyFsExitCode = config.trivyFsExitCode ?: '1'
        c.trivyImageExitCode = config.trivyImageExitCode ?: '1'

        c.buildServices = ([c.runtimeService, c.testService] + c.startDeps).unique().join(' ')
        c.startServices = c.startDeps.join(' ')
        c.projectName = "microshop-${c.serviceName}-${System.getenv('BRANCH_NAME') ?: 'local'}-${System.getenv('BUILD_NUMBER') ?: '0'}"
            .replaceAll('[^A-Za-z0-9_.-]', '-')

        return c
    }
}