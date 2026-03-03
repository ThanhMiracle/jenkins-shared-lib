# Jenkins Shared Library: `vars/microshopCi.groovy` — Detailed Notes

> This document explains (line-by-line by concept) the `microshopCi` shared-library step you’re using.
> It’s written so you can copy/paste into your own notes and quickly modify the pipeline later.

---

## 1) What this file is

- This file lives in a Jenkins Shared Library repo at: `vars/microshopCi.groovy`
- Anything in `vars/` becomes a callable step in Jenkins Pipeline.
  - File name `microshopCi.groovy` => pipeline step name `microshopCi(...)`
- The step is implemented as a function `def call(Map cfg = [:])`:
  - Jenkins automatically calls `call(...)` when you run `microshopCi(...)` in a Jenkinsfile.

### Example usage in a Jenkinsfile

```groovy
@Library('micro-lib') _
microshopCi(
  dockerCredsId: 'dockerhub-creds',
  composeFiles: ['docker-compose.yml', 'docker-compose.ci.yml'],
  testBranch: 'dev',
  pushBranch: 'main'
)
```

---

## 2) The configuration block (top of the file)

### `cfg` map
The pipeline is customizable using a `Map cfg` (key/value overrides from Jenkinsfile).

### Key settings

#### `dockerCredsId`
```groovy
final String dockerCredsId = (cfg.dockerCredsId ?: 'dockerhub-creds').toString()
```
- Jenkins credentials ID used for Docker Hub login.
- Must point to a **Username/Password** credential in Jenkins.

#### `composeFiles`
```groovy
final List<String> composeFiles = (cfg.composeFiles ?: ['docker-compose.yml', 'docker-compose.ci.yml']) as List<String>
```
- Which compose files to use (supports multiple `-f` files).
- The pipeline renders these as:
  - `-f 'docker-compose.yml' -f 'docker-compose.ci.yml'`

#### Branch controls: `testBranch`, `pushBranch`, `deployBranch`
```groovy
final String testBranch   = (cfg.testBranch   ?: 'dev').toString()
final String pushBranch   = (cfg.pushBranch   ?: 'main').toString()
final String deployBranch = (cfg.deployBranch ?: 'main').toString()
```
These decide **when stages run**:
- **Test stage** runs only on `testBranch` (default `dev`)
- **Push Images stage** runs only on `pushBranch` (default `main`)
- **Deploy stage** runs only on `deployBranch` (default `main`)

This prevents expensive or dangerous actions from running on every branch.

#### Infra services & test services
```groovy
final List<String> infraServices = (cfg.infraServices ?: ['postgres', 'rabbit', 'mailhog']) as List<String>
final List<String> testServices  = (cfg.testServices  ?: ['auth-tests', 'product-tests', 'order-tests', 'payment-tests', 'notify-tests']) as List<String>
```

- `infraServices`:
  - Services you want running **in the background** for tests (DB, RabbitMQ, Mailhog).
  - Started using `docker compose up -d ...`.
- `testServices`:
  - Compose services that run tests and then exit (typically `pytest` runners).
  - Executed using `docker compose run --rm <svc>`.

#### `parallelTests`
```groovy
final boolean parallelTests = (cfg.parallelTests == null ? false : cfg.parallelTests as boolean)
```
- If `true`, Jenkins will run each test service in parallel branches.
- If `false`, tests run sequentially in one shell script.

---

## 3) Branch detection fallback logic

### Why this exists
- In Multibranch Pipeline jobs, Jenkins sets `env.BRANCH_NAME`.
- In some non-multibranch jobs, you might only get `env.GIT_BRANCH` (often like `origin/dev`).
- This pipeline supports both.

### Bash snippet used in `Preflight` for printing branch
```bash
BR="${BRANCH_NAME:-}"
if [ -z "$BR" ] && [ -n "${GIT_BRANCH:-}" ]; then
  BR="${GIT_BRANCH#origin/}"
  BR="${BR#refs/heads/}"
fi
echo "$BR"
```

### Groovy stage conditions use similar logic
In `when { expression { ... } }`, it does:
- pick `env.BRANCH_NAME` or `env.GIT_BRANCH`
- strip `origin/` or `refs/heads/`
- compare with `testBranch/pushBranch/deployBranch`

---

## 4) Declarative Pipeline skeleton

```groovy
pipeline {
  agent any
  options { timestamps(); disableConcurrentBuilds() }
  environment { DOCKER_BUILDKIT="1"; COMPOSE_DOCKER_CLI_BUILD="1"; COMPOSE_PROJECT_NAME="microshop-ci-${BUILD_NUMBER}" }
  stages { ... }
  post { always { ... } }
}
```

### `agent any`
- Jenkins can run this on any available agent node.

### `options`
- `timestamps()` adds timestamps to logs (very useful for debugging).
- `disableConcurrentBuilds()` prevents two builds of the same job running at once (avoids container/network collisions).

### `environment`
- `DOCKER_BUILDKIT=1` enables BuildKit for faster & better Docker builds.
- `COMPOSE_DOCKER_CLI_BUILD=1` makes `docker compose build` use the Docker CLI builder.
- `COMPOSE_PROJECT_NAME=microshop-ci-${BUILD_NUMBER}`:
  - **critical for isolation**
  - ensures every build gets unique networks/containers (no conflicts with other builds).

---

## 5) Stage: Checkout

```groovy
stage('Checkout') {
  steps { checkout scm }
}
```

- Pulls your repo source into the Jenkins workspace.
- `scm` is the Jenkins job’s configured Git source (multibranch uses the branch being built).

---

## 6) Stage: Preflight

Purpose: **print useful diagnostics early** so failures are easier to debug.

It prints:
- resolved branch name
- hostname, user, workspace
- docker version
- compose version (v2 or v1)
- DNS sanity via `getent hosts api.github.com`

If DNS is broken, you’ll see it here before build/test fails.

---

## 7) Stage: Build

Purpose: validate compose + build images.

### Compose command selection
```bash
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
else
  COMPOSE="docker-compose"
fi
```
- Supports both v2 (`docker compose`) and v1 (`docker-compose`) on different nodes.

### Build command `C`
```bash
C="$COMPOSE -p 'microshop-ci-<build>' -f '...' -f '...'"
```

### Steps inside Build
- `C config`:
  - validates the compose config (great for catching YAML mistakes).
- `C pull --ignore-pull-failures`:
  - best-effort pulls base images.
- `C build --pull`:
  - builds the images referenced by your compose files, pulling latest base images.
- `C images` prints summary.

---

## 8) Stage: Test (branch-gated)

Runs only when the resolved branch equals `testBranch` (default `dev`).

### What it does (sequential mode)
1) Pick compose command + build `C`
2) `mkdir -p ci-artifacts` (logs stored here)
3) Start infra services in background:
   - `C up -d postgres rabbit mailhog`
4) Best-effort wait for healthchecks:
   - `C up -d --wait ... || true`
   - Not all compose versions support `--wait`, hence `|| true`.
5) Run each test container:
   - `C run --rm auth-tests`
   - output is tee’d into `ci-artifacts/<svc>.out.log`
6) If any test fails:
   - print `C ps`
   - print `C logs --tail=300`
   - exit with that test’s exit code (fails the build)

### How exit codes are captured
Because output is piped to `tee`, bash needs `PIPESTATUS[0]`:
```bash
$C run --rm "$svc" 2>&1 | tee "ci-artifacts/$svc.out.log"
rc=${PIPESTATUS[0]}
```

### Parallel mode (optional)
If `parallelTests: true`:
- Infra is started once.
- Each test service runs in its own parallel branch.
- Each branch writes its own log file in `ci-artifacts/`.

---

## 9) Stage: Push Images (branch-gated)

Runs only on `pushBranch` (default `main`).

### Credentials
Uses:
```groovy
withCredentials([usernamePassword(credentialsId: dockerCredsId, ...)])
```
This injects:
- `DOCKERHUB_USER`
- `DOCKERHUB_PASS`

### Tagging strategy
- Compute short SHA:
  - `GIT_SHA=$(git rev-parse --short=12 HEAD)`
- Discover images referenced by compose:
  - `C config --images`
- For each image, derive repo name and retag:
  - `dest_sha = DOCKERHUB_USER/<repo>:<GIT_SHA>`
  - `dest_branch_latest = DOCKERHUB_USER/<repo>:main-latest`
- Push both tags.

**Important assumption:**  
This works best when your build produces local images whose names match `C config --images` output.

---

## 10) Stage: Deploy (branch-gated placeholder)

Runs only on `deployBranch` (default `main`).

Currently it only prints a message. You’ll later replace this with:
- Helm deploy / kubectl apply
- Terraform apply
- ArgoCD sync
- etc.

---

## 11) `post { always { ... } }` cleanup & artifacts

This block runs **no matter what** (success/failure/aborted).

### Collect artifacts
It writes to `ci-artifacts/`:
- `compose-ps.txt`
- `compose-logs.txt`
- `compose-config.rendered.yml`
- `docker-ps-a.txt`
- `docker-networks.txt`

### Cleanup
```bash
$C down -v --remove-orphans || true
```
- Stops/removes containers
- Removes volumes created by the compose project (`-v`)
- Removes orphan containers (`--remove-orphans`)
- `|| true` ensures cleanup doesn’t fail the pipeline

### Archive + clean workspace
```groovy
archiveArtifacts artifacts: 'ci-artifacts/*', allowEmptyArchive: true
cleanWs()
```
- `archiveArtifacts`: lets you download logs from Jenkins UI.
- `cleanWs()`: keeps the Jenkins workspace clean.

---

## 12) Common modifications you’ll likely do

### A) Run tests on PRs too
- Multibranch PR builds may have branch names like `PR-12`.
- Update `when` logic to include PR builds, or use `CHANGE_ID` env var.

### B) Add caching / build optimizations
- Use BuildKit cache mounts
- Use `--cache-from` or registry-based caching

### C) Push to registry other than Docker Hub
- Swap login + push commands for ECR/GCR/ACR.

### D) Add deploy real logic
- Pull the `GIT_SHA` tag you pushed
- Update manifests/Helm values
- Apply to Kubernetes

---

## Quick Cheat Sheet

- `composeFiles`: which YAML files define CI compose
- `infraServices`: shared dependencies started via `up -d`
- `testServices`: test runner services executed via `run --rm`
- `COMPOSE_PROJECT_NAME`: isolates containers per build
- `Test` runs only on `testBranch`
- `Push` runs only on `pushBranch`
- `Deploy` runs only on `deployBranch`
- `post/always`: collects logs + tears everything down safely

