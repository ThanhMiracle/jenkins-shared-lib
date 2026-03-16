def call(Map config = [:]) {
    def envFile = config.get('envFile', '.env')
    def envCredentials = config.get('envCredentials', [])
    def extraEnv = config.get('extraEnv', [])

    def bindings = envCredentials.collect { id ->
        string(credentialsId: id, variable: id)
    }

    withCredentials(bindings) {
        def lines = []
        envCredentials.each { id ->
            lines << "${id}=${env[id]}"
        }
        extraEnv.each { item ->
            lines << item
        }

        writeFile file: envFile, text: lines.join('\n') + '\n'
        sh "chmod 600 ${envFile}"
    }
}