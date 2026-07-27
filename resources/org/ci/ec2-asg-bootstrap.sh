#!/usr/bin/env bash
set -Eeuo pipefail

exec > >(tee -a /var/log/app-bootstrap.log | logger -t app-bootstrap -s 2>/dev/console) 2>&1

AWS_REGION="__AWS_REGION__"
ARTIFACT_URI="__ARTIFACT_URI__"
ENV_PARAMETER="__ENV_PARAMETER__"
RELEASE="__RELEASE__"
COMPOSE_VERSION="v2.30.3"

if command -v dnf >/dev/null 2>&1; then
  dnf install -y docker awscli tar gzip curl
elif command -v yum >/dev/null 2>&1; then
  yum install -y docker awscli tar gzip curl
else
  echo "This bootstrap supports Amazon Linux only" >&2
  exit 1
fi

systemctl enable --now docker

if ! docker compose version >/dev/null 2>&1; then
  case "$(uname -m)" in
    x86_64) COMPOSE_ARCH="x86_64" ;;
    aarch64|arm64) COMPOSE_ARCH="aarch64" ;;
    *) echo "Unsupported CPU architecture: $(uname -m)" >&2; exit 1 ;;
  esac
  install -d /usr/local/lib/docker/cli-plugins
  curl --fail --location --retry 3 \
    "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-${COMPOSE_ARCH}" \
    --output /usr/local/lib/docker/cli-plugins/docker-compose
  chmod 0755 /usr/local/lib/docker/cli-plugins/docker-compose
fi

mkdir -p "/opt/app/releases/${RELEASE}"
aws s3 cp "${ARTIFACT_URI}" "/tmp/${RELEASE}.tgz" --region "${AWS_REGION}"
tar -xzf "/tmp/${RELEASE}.tgz" -C "/opt/app/releases/${RELEASE}"

aws ssm get-parameter \
  --name "${ENV_PARAMETER}" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text \
  --region "${AWS_REGION}" > "/opt/app/releases/${RELEASE}/.env"
chmod 600 "/opt/app/releases/${RELEASE}/.env"

ln -sfn "/opt/app/releases/${RELEASE}" /opt/app/current
cd /opt/app/current
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d --remove-orphans
docker image prune -af --filter "until=168h"
