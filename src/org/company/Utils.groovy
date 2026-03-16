package org.company

class Utils implements Serializable {
    def steps

    Utils(steps) {
        this.steps = steps
    }

    def logInfo(String msg) {
        steps.echo "[INFO] ${msg}"
    }

    def logWarn(String msg) {
        steps.echo "[WARN] ${msg}"
    }
}