package org.ci

class TrivyHelper implements Serializable {
    static void fsScan(script, config) {
        script.sh """
            set -eux
            trivy fs \
              --scanners vuln,secret,misconfig \
              --severity "${config.trivySeverity}" \
              --exit-code "${config.trivyFsExitCode}" \
              --format table \
              --output "reports/${config.serviceName}/trivy/fs-scan.txt" \
              "${config.serviceDir}"
        """
    }

    static void imageScan(script, config) {
        script.sh """
            set -eux
            mkdir -p "reports/${config.serviceName}/trivy"
            mkdir -p .trivycache

            IMAGE_NAME="micro-ecom/${config.serviceName}:ci"

            docker run --rm \
              -v /var/run/docker.sock:/var/run/docker.sock \
              -v "\$PWD:/workspace" \
              -v "\$PWD/.trivycache:/root/.cache/trivy" \
              aquasec/trivy:latest image \
              --severity "${config.trivySeverity}" \
              --exit-code "${config.trivyImageExitCode}" \
              --format table \
              --output "/workspace/reports/${config.serviceName}/trivy/image-scan.txt" \
              "${IMAGE_NAME}"
        """
    }
}