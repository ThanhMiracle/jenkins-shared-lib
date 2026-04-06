package org.ci

class ReportHelper implements Serializable {
    static void collect(script, config) {
        ComposeHelper.collectLogsAndCleanup(script, config)

        script.archiveArtifacts(
            artifacts: "compose-${config.serviceName}.log, images-${config.serviceName}.log, reports/${config.serviceName}/*.xml, reports/${config.serviceName}/trivy/*",
            allowEmptyArchive: true
        )

        script.junit testResults: config.reportGlob, allowEmptyResults: true
        script.cleanWs()
    }
}