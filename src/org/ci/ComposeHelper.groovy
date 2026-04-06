package org.ci

class ComposeHelper implements Serializable {
    static String files() {
        return '-f docker-compose.yml -f docker-compose.ci.yml'
    }

    static void prepare(script, config) {
        script.sh """
            set -eux
            mkdir -p "reports/${config.serviceName}"
            mkdir -p "reports/${config.serviceName}/trivy"
            rm -f "reports/${config.serviceName}"/*.xml || true
            rm -f "reports/${config.serviceName}/trivy"/* || true
            docker version
            docker compose version
        """
    }

    static void build(script, config) {
        script.sh """
            set -eux
            docker compose ${files()} -p "${config.projectName}" build ${config.buildServices}
        """
    }

    static void upDeps(script, config) {
        script.sh """
            set -eux
            docker compose ${files()} -p "${config.projectName}" up -d ${config.startServices}
        """
    }

    static void runTests(script, config) {
        script.sh """
            set -eux
            docker compose ${files()} -p "${config.projectName}" run --rm ${config.testService}
            echo "=== reports after tests ==="
            find reports -maxdepth 3 -type f | sort || true
            ls -la "reports/${config.serviceName}" || true
        """
    }

    static void collectLogsAndCleanup(script, config) {
        script.sh """
            set +e
            docker compose ${files()} -p "${config.projectName}" logs --no-color > "compose-${config.serviceName}.log" || true
            docker compose ${files()} -p "${config.projectName}" images > "images-${config.serviceName}.log" || true
            docker compose ${files()} -p "${config.projectName}" down -v --remove-orphans || true
        """
    }
}