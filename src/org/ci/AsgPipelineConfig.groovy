package org.ci

class AsgPipelineConfig implements Serializable {
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
        def c = new AsgPipelineConfig()
        c.dockerCredentialsId = (raw.dockerCredentialsId ?: '').toString()
        c.dockerRegistry = (raw.dockerRegistry ?: 'docker.io').toString()
        c.frontendImage = (raw.frontendImage ?: '').toString()
        c.backendImage = (raw.backendImage ?: '').toString()
        c.frontendContext = (raw.frontendContext ?: './frontend').toString()
        c.backendContext = (raw.backendContext ?: './backend').toString()
        c.frontendService = (raw.frontendService ?: 'frontend').toString()
        c.backendService = (raw.backendService ?: 'api').toString()
        c.composeFile = (raw.composeFile ?: 'docker-compose.yml').toString()
        c.nginxDir = (raw.nginxDir ?: 'nginx').toString()
        c.testCommand = (raw.testCommand ?: '''
            docker build --target test \
              -t fullstack-backend-ci:${RELEASE_TAG} ./backend
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
              fullstack-backend-ci:${RELEASE_TAG}
        ''').toString()
        c.awsCredentialsId = (raw.awsCredentialsId ?: '').toString()
        c.awsRegion = (raw.awsRegion ?: 'ap-southeast-1').toString()
        c.artifactBucket = (raw.artifactBucket ?: '').toString()
        c.environmentParameter = (raw.environmentParameter ?: '').toString()
        c.autoScalingGroup = (raw.autoScalingGroup ?: '').toString()
        c.launchTemplateId = (raw.launchTemplateId ?: '').toString()
        c.trivyEnabled = raw.get('trivyEnabled', true) as boolean
        return c
    }

    String configurationValidationError() {
        def missing = []
        if (!frontendImage?.trim()) {
            missing << 'frontendImage'
        }
        if (!backendImage?.trim()) {
            missing << 'backendImage'
        }

        return missing ? "${missing.join(', ')} required" : ''
    }

    String deploymentValidationError() {
        def missing = []
        if (!artifactBucket?.trim()) {
            missing << 'artifactBucket'
        }
        if (!environmentParameter?.trim()) {
            missing << 'environmentParameter'
        }
        if (!autoScalingGroup?.trim()) {
            missing << 'autoScalingGroup'
        }
        if (!launchTemplateId?.trim()) {
            missing << 'launchTemplateId'
        }

        return missing ? "${missing.join(', ')} required on main" : ''
    }
}
