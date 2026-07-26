package org.ci

class AsgPipelineConfig implements Serializable {
    String deployBranch
    String dockerCredentialsId
    String dockerRegistry
    String frontendImage
    String backendImage
    String frontendContext
    String backendContext
    String frontendService
    String backendService
    String composeFile
    String nginxDir
    String testCommand
    String awsCredentialsId
    String awsRegion
    String artifactBucket
    String environmentParameter
    String autoScalingGroup
    String launchTemplateId
    boolean trivyEnabled

    static AsgPipelineConfig from(Map raw) {
        [
            'dockerCredentialsId',
            'frontendImage',
            'backendImage',
            'artifactBucket',
            'environmentParameter',
            'autoScalingGroup',
            'launchTemplateId'
        ].each { key ->
            if (!raw[key]) {
                throw new IllegalArgumentException("${key} is required")
            }
        }

        def c = new AsgPipelineConfig()
        c.deployBranch = (raw.deployBranch ?: 'main').toString()
        c.dockerCredentialsId = raw.dockerCredentialsId.toString()
        c.dockerRegistry = (raw.dockerRegistry ?: 'docker.io').toString()
        c.frontendImage = raw.frontendImage.toString()
        c.backendImage = raw.backendImage.toString()
        c.frontendContext = (raw.frontendContext ?: './frontend').toString()
        c.backendContext = (raw.backendContext ?: './backend').toString()
        c.frontendService = (raw.frontendService ?: 'frontend').toString()
        c.backendService = (raw.backendService ?: 'api').toString()
        c.composeFile = (raw.composeFile ?: 'docker-compose.yml').toString()
        c.nginxDir = (raw.nginxDir ?: 'nginx').toString()
        c.testCommand = (raw.testCommand ?: '''
            docker build -t fullstack-backend-ci:${RELEASE_TAG} ./backend
            docker run --rm \
              -e DATABASE_URL=sqlite:////tmp/app.db \
              -e JWT_SECRET=ci-secret \
              -e JWT_EXPIRE_MINUTES=120 \
              -e MINIO_ENDPOINT=127.0.0.1:9000 \
              -e MINIO_ACCESS_KEY=minioadmin \
              -e MINIO_SECRET_KEY=minioadmin \
              -e MINIO_BUCKET=uploads \
              -e MINIO_SECURE=false \
              -e MINIO_PUBLIC_URL=http://127.0.0.1:9000 \
              -v "$PWD/backend/tests:/app/tests:ro" \
              fullstack-backend-ci:${RELEASE_TAG} \
              pytest -q -p no:cacheprovider /app/tests
        ''').toString()
        c.awsCredentialsId = (raw.awsCredentialsId ?: '').toString()
        c.awsRegion = (raw.awsRegion ?: 'ap-southeast-1').toString()
        c.artifactBucket = raw.artifactBucket.toString()
        c.environmentParameter = raw.environmentParameter.toString()
        c.autoScalingGroup = raw.autoScalingGroup.toString()
        c.launchTemplateId = raw.launchTemplateId.toString()
        c.trivyEnabled = raw.get('trivyEnabled', true) as boolean
        return c
    }
}
