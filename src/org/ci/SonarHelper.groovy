package org.ci

class SonarHelper implements Serializable {
    static void scan(script, config) {
        def scannerHome = script.tool config.sonarScannerTool
        script.withSonarQubeEnv(config.sonarServer) {
            script.sh """
                set -eux
                cd "${config.serviceDir}"
                "${scannerHome}/bin/sonar-scanner" \
                  -Dsonar.projectKey="${config.sonarProjectKey}" \
                  -Dsonar.projectName="${config.sonarProjectName}" \
                  -Dsonar.sources="${config.sonarSources}" \
                  -Dsonar.exclusions="${config.sonarExclusions}" \
                  ${config.sonarExtraArgs}
            """
        }
    }

    static void qualityGate(script) {
        script.timeout(time: 10, unit: 'MINUTES') {
            script.waitForQualityGate abortPipeline: true
        }
    }
}