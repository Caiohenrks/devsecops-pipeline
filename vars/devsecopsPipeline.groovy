def call(Map config = [:]) {
    pipeline {
        agent any

        environment {
            // --- URLs / hosts (lab). Troque aqui ou passe no call: devsecopsPipeline(REGISTRY_PUSH: '...') ---
            GIT_INTERNAL_HOST = 'gitea'
            REGISTRY_PUSH     = '127.0.0.1:5000'
            REGISTRY_PULL     = 'nexus:8082'
            REGISTRY_REPO     = 'docker'
            K8S_SERVER        = 'https://host.docker.internal:6443'
            DOCKER_NETWORK    = 'infra_devsecops-network'
            MAIL_FROM         = 'contato@henrks.com'
            TRIVY_CACHE_ROOT  = '/var/tmp/trivy'

            // --- IDs das credenciais no Jenkins. A senha/chave fica no Jenkins, nunca neste arquivo ---
            CRED_GIT_SSH          = 'gitea-ssh'
            CRED_NEXUS            = 'nexus-account'
            CRED_COSIGN_KEY       = 'cosign-key'
            CRED_COSIGN_PUB       = 'cosign-pub'
            CRED_COSIGN_PASSWORD  = 'cosign-password'
            CRED_KUBECONFIG       = 'k3s-kubeconfig'

            // --- Imagens das ferramentas ---
            GITLEAKS_IMAGE   = 'ghcr.io/gitleaks/gitleaks:v8.28.0'
            SEMGREP_IMAGE    = 'semgrep/semgrep:1.128.0'
            TRIVY_IMAGE      = 'aquasec/trivy:0.74.0'
            CYCLONEDX_IMAGE  = 'ghcr.io/cdxgen/cdxgen:v12'
            MAVEN_IMAGE      = 'maven:3.9.9-eclipse-temurin-21'
            COSIGN_IMAGE     = 'cgr.dev/chainguard/cosign:latest@sha256:2af5cabe038577e02b21be3b6e2622c2cd2659dcdef2bdc0fbf5f64e16965a88'
        }

        stages {
            stage('Prepare environment') {
                steps {
                    script {
                        bindWebhookParams()
                        resolveEnvironment(config)
                        cleanWs()
                        env.k8sDir = (config.k8sDir ?: 'k8s') as String
                        env.dockerContext = (config.context ?: '.') as String
                        env.dockerfile = (config.dockerfile ?: 'Dockerfile') as String
                        env.sshUrl = (env.ssh_url ?: '').replace('localhost', env.GIT_INTERNAL_HOST)
                        env.registry = "${env.REGISTRY_PUSH}/${env.REGISTRY_REPO}"
                        env.srcDir = "${env.WORKSPACE}/${env.branch}"
                        env.reportsDir = "${env.WORKSPACE}/reports"
                        env.trivyFsCache = "${env.TRIVY_CACHE_ROOT}/${env.JOB_BASE_NAME}/fs"
                        env.trivySbomCache = "${env.TRIVY_CACHE_ROOT}/${env.JOB_BASE_NAME}/sbom"
                        env.trivyImageCache = "${env.TRIVY_CACHE_ROOT}/${env.JOB_BASE_NAME}/image"
                        currentBuild.description = env.repo_full_name ?: 'unknown-repo'
                        sh "mkdir -p '${env.reportsDir}'"
                    }
                }
            }

            stage('Clone repository') {
                steps {
                    script {
                        explainAndFail(
                            'Clone',
                            "Não foi possível clonar o repo. Confira ${env.CRED_GIT_SSH}, o SHA (after) e se ${env.GIT_INTERNAL_HOST} resolve na rede Docker."
                        ) {
                            def gitRef = (env.after ?: '').trim() ? env.after : "*/${env.branch}"
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: gitRef]],
                                extensions: [
                                    [$class: 'RelativeTargetDirectory', relativeTargetDir: env.branch]
                                ],
                                userRemoteConfigs: [[
                                    credentialsId: env.CRED_GIT_SSH,
                                    url: env.sshUrl
                                ]]
                            ])
                            env.commit = env.after ?: sh(returnStdout: true, script: "git -C '${env.srcDir}' rev-parse HEAD").trim()
                            env.commitEmail = env.commit_email ?: sh(returnStdout: true, script: "git -C '${env.srcDir}' log -1 --pretty=%ae").trim()
                            env.commitAuthor = env.commit_author ?: sh(returnStdout: true, script: "git -C '${env.srcDir}' log -1 --pretty=%an").trim()
                            env.shortSha = env.commit.substring(0, 12)
                            env.image = "${env.registry}/${env.repo_full_name}:${env.shortSha}"
                            env.k8sImage = "${env.REGISTRY_PULL}/${env.REGISTRY_REPO}/${env.repo_full_name}:${env.shortSha}"
                            echo "Commit ${env.commit}"
                            echo "Image ${env.image}"
                            echo "K8s image ${env.k8sImage}"
                            echo "Notify ${env.commitAuthor} <${env.commitEmail}>"
                            def desc = "${env.repo_full_name} @ ${env.shortSha}"
                            if (env.DEPLOY_ENV) {
                                desc = "${desc} · ${env.DEPLOY_ENV}"
                            }
                            if (env.DEPLOY != 'true') {
                                desc = "${desc} · CI only"
                            }
                            currentBuild.description = desc
                        }
                    }
                }
            }

            stage('CycloneDX') {
                steps {
                    script {
                        explainAndFail(
                            'CycloneDX',
                            'Falha da ferramenta ao gerar o SBOM do código. Relatório: reports/sbom-cyclonedx.json.'
                        ) {
                            sh """
                                if [ -f '${env.srcDir}/pom.xml' ]; then
                                  docker run --rm --user 0 --volumes-from jenkins \
                                    -v maven-cache:/root/.m2 \
                                    -w '${env.srcDir}' \
                                    ${env.MAVEN_IMAGE} \
                                    mvn -B -DskipTests dependency:resolve
                                  docker run --rm --user 0 --volumes-from jenkins \
                                    -v maven-cache:/root/.m2 \
                                    -w '${env.srcDir}' \
                                    ${env.CYCLONEDX_IMAGE} \
                                    -r -o '${env.reportsDir}/sbom-cyclonedx.json' \
                                    .
                                else
                                  docker run --rm --user 0 --volumes-from jenkins \
                                    -w '${env.srcDir}' \
                                    ${env.CYCLONEDX_IMAGE} \
                                    --no-install-deps -r \
                                    -o '${env.reportsDir}/sbom-cyclonedx.json' \
                                    .
                                fi
                            """
                        }
                    }
                }
            }

            stage('Security scans') {
                parallel {
                    stage('Gitleaks') {
                        steps {
                            script {
                                recordFailure(
                                    'GITLEAKS_ERROR',
                                    'Gitleaks',
                                    'Secret no git ou o scanner saiu com erro. Relatório: reports/gitleaks.json.'
                                ) {
                                    sh """
                                        docker run --rm --volumes-from jenkins \
                                          -w '${env.srcDir}' \
                                          ${env.GITLEAKS_IMAGE} \
                                          detect --source . --verbose \
                                          --report-path '${env.reportsDir}/gitleaks.json' \
                                          --report-format json
                                    """
                                }
                            }
                        }
                    }

                    stage('Semgrep') {
                        steps {
                            script {
                                recordFailure(
                                    'SEMGREP_ERROR',
                                    'Semgrep',
                                    'O Semgrep falhou (parse, regra ou ferramenta). Relatório: reports/semgrep.json.'
                                ) {
                                    sh """
                                        docker run --rm --volumes-from jenkins \
                                          -e CI=false \
                                          --entrypoint sh \
                                          ${env.SEMGREP_IMAGE} \
                                          -c 'mkdir -p /src && find /src -mindepth 1 -delete && cp -a "${env.srcDir}/." /src/ && rm -rf /src/.git && semgrep scan --config auto --json-output="${env.reportsDir}/semgrep.json" /src'
                                    """
                                }
                            }
                        }
                    }

                    stage('Trivy FS') {
                        steps {
                            script {
                                recordFailure(
                                    'TRIVYFS_ERROR',
                                    'Trivy FS',
                                    'HIGH/CRITICAL no filesystem ou erro do scanner. Relatório: reports/trivy-fs.json. Dependência Java/npm/Go entra no Trivy SBOM.'
                                ) {
                                    sh """
                                        docker run --rm --volumes-from jenkins \
                                          -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                          -e TRIVY_CACHE_DIR='${env.trivyFsCache}' \
                                          -w '${env.srcDir}' \
                                          ${env.TRIVY_IMAGE} \
                                          fs --offline-scan --exit-code 0 --format json --output '${env.reportsDir}/trivy-fs.json' .
                                    """
                                    sh """
                                        docker run --rm --volumes-from jenkins \
                                          -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                          -e TRIVY_CACHE_DIR='${env.trivyFsCache}' \
                                          -w '${env.srcDir}' \
                                          ${env.TRIVY_IMAGE} \
                                          fs --offline-scan --exit-code 1 --severity HIGH,CRITICAL .
                                    """
                                }
                            }
                        }
                    }

                    stage('Trivy SBOM') {
                        steps {
                            script {
                                recordFailure(
                                    'TRIVYSBOM_ERROR',
                                    'Trivy SBOM',
                                    'HIGH/CRITICAL no SBOM do código ou erro do scanner. Relatório: reports/trivy-sbom.json.'
                                ) {
                                    sh """
                                        docker run --rm --volumes-from jenkins \
                                          -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                          -e TRIVY_CACHE_DIR='${env.trivySbomCache}' \
                                          ${env.TRIVY_IMAGE} \
                                          sbom --exit-code 0 --format json --output '${env.reportsDir}/trivy-sbom.json' \
                                          '${env.reportsDir}/sbom-cyclonedx.json'
                                    """
                                    sh """
                                        docker run --rm --volumes-from jenkins \
                                          -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                          -e TRIVY_CACHE_DIR='${env.trivySbomCache}' \
                                          ${env.TRIVY_IMAGE} \
                                          sbom --exit-code 1 --severity HIGH,CRITICAL \
                                          '${env.reportsDir}/sbom-cyclonedx.json'
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Security gate') {
                steps {
                    script {
                        def failed = [
                            env.GITLEAKS_ERROR,
                            env.SEMGREP_ERROR,
                            env.TRIVYFS_ERROR,
                            env.TRIVYSBOM_ERROR
                        ].findAll { it }
                        if (failed) {
                            env.PIPELINE_ERROR = failed.join(' | ')
                            error('Security scans falharam:\n- ' + failed.join('\n- '))
                        }
                    }
                }
            }

            stage('Build image') {
                steps {
                    script {
                        explainAndFail(
                            'Build image',
                            'docker build falhou (Dockerfile, dependência ou download). A imagem não foi criada.'
                        ) {
                            dir(env.branch) {
                                sh "docker build -f '${env.dockerfile}' -t '${env.image}' '${env.dockerContext}'"
                            }
                        }
                    }
                }
            }

            stage('CycloneDX image') {
                steps {
                    script {
                        explainAndFail(
                            'CycloneDX image',
                            'Não gerou o SBOM da imagem (Trivy --format cyclonedx). Relatório: reports/sbom-image-cyclonedx.json.'
                        ) {
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v /var/run/docker.sock:/var/run/docker.sock \
                                  -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                  -e TRIVY_CACHE_DIR='${env.trivyImageCache}' \
                                  ${env.TRIVY_IMAGE} \
                                  image --format cyclonedx --output '${env.reportsDir}/sbom-image-cyclonedx.json' \
                                  '${env.image}'
                            """
                        }
                    }
                }
            }

            stage('Trivy SBOM image') {
                steps {
                    script {
                        explainAndFail(
                            'Trivy SBOM image',
                            'HIGH/CRITICAL no SBOM da imagem (ou o scanner falhou). Relatório: reports/trivy-sbom-image.json.'
                        ) {
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                  -e TRIVY_CACHE_DIR='${env.trivyImageCache}' \
                                  ${env.TRIVY_IMAGE} \
                                  sbom --exit-code 0 --format json --output '${env.reportsDir}/trivy-sbom-image.json' \
                                  '${env.reportsDir}/sbom-image-cyclonedx.json'
                            """
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                  -e TRIVY_CACHE_DIR='${env.trivyImageCache}' \
                                  ${env.TRIVY_IMAGE} \
                                  sbom --exit-code 1 --severity HIGH,CRITICAL \
                                  '${env.reportsDir}/sbom-image-cyclonedx.json'
                            """
                        }
                    }
                }
            }

            stage('Trivy image') {
                steps {
                    script {
                        explainAndFail(
                            'Trivy image',
                            'A imagem tem CVE HIGH/CRITICAL (ou o scanner falhou). Não segue para o Nexus. Relatório: reports/trivy-image.json.'
                        ) {
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v /var/run/docker.sock:/var/run/docker.sock \
                                  -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                  -e TRIVY_CACHE_DIR='${env.trivyImageCache}' \
                                  ${env.TRIVY_IMAGE} \
                                  image --exit-code 0 --format json --output '${env.reportsDir}/trivy-image.json' '${env.image}'
                            """
                            sh """
                                docker run --rm \
                                  -v /var/run/docker.sock:/var/run/docker.sock \
                                  -v trivy-cache:${env.TRIVY_CACHE_ROOT} \
                                  -e TRIVY_CACHE_DIR='${env.trivyImageCache}' \
                                  ${env.TRIVY_IMAGE} \
                                  image --exit-code 1 --severity HIGH,CRITICAL '${env.image}'
                            """
                        }
                    }
                }
            }

            stage('Push image') {
                when { expression { return env.DEPLOY == 'true' } }
                steps {
                    script {
                        explainAndFail(
                            'Push / Cosign',
                            "Login/push no registry ou Cosign falhou. Confira ${env.CRED_NEXUS}, ${env.CRED_COSIGN_KEY} e a rede ${env.DOCKER_NETWORK}."
                        ) {
                            withCredentials([
                                usernamePassword(credentialsId: env.CRED_NEXUS, usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS'),
                                file(credentialsId: env.CRED_COSIGN_KEY, variable: 'COSIGN_KEY'),
                                file(credentialsId: env.CRED_COSIGN_PUB, variable: 'COSIGN_PUB'),
                                string(credentialsId: env.CRED_COSIGN_PASSWORD, variable: 'COSIGN_PASSWORD')
                            ]) {
                                sh 'echo "$NEXUS_PASS" | docker login "$REGISTRY_PUSH" -u "$NEXUS_USER" --password-stdin'
                                sh "docker push '${env.image}'"
                                def digest = sh(
                                    returnStdout: true,
                                    script: "docker inspect --format='{{index .RepoDigests 0}}' '${env.image}'"
                                ).trim()
                                env.imageRef = digest.replace(env.REGISTRY_PUSH, env.REGISTRY_PULL)
                                echo "Signing ${env.imageRef}"
                                withEnv([
                                    "SBOM_PATH=${env.reportsDir}/sbom-image-cyclonedx.json",
                                    "SBOM_ATTESTATION=${env.reportsDir}/sbom-image-attestation.json"
                                ]) {
                                    sh '''
                                        docker run --rm --user 0 --volumes-from jenkins \
                                          --network "$DOCKER_NETWORK" \
                                          -e COSIGN_PASSWORD \
                                          "$COSIGN_IMAGE" \
                                          sign --yes \
                                          --allow-http-registry --allow-insecure-registry \
                                          --registry-username "$NEXUS_USER" \
                                          --registry-password "$NEXUS_PASS" \
                                          --key "$COSIGN_KEY" \
                                          "$imageRef"
                                    '''
                                    sh '''
                                        docker run --rm --user 0 --volumes-from jenkins \
                                          --network "$DOCKER_NETWORK" \
                                          -e COSIGN_PASSWORD \
                                          "$COSIGN_IMAGE" \
                                          attest --yes \
                                          --type cyclonedx \
                                          --predicate "$SBOM_PATH" \
                                          --allow-http-registry --allow-insecure-registry \
                                          --registry-username "$NEXUS_USER" \
                                          --registry-password "$NEXUS_PASS" \
                                          --key "$COSIGN_KEY" \
                                          "$imageRef"
                                    '''
                                    sh '''
                                        docker run --rm --user 0 --volumes-from jenkins \
                                          --network "$DOCKER_NETWORK" \
                                          "$COSIGN_IMAGE" \
                                          verify \
                                          --allow-http-registry --allow-insecure-registry \
                                          --registry-username "$NEXUS_USER" \
                                          --registry-password "$NEXUS_PASS" \
                                          --key "$COSIGN_PUB" \
                                          "$imageRef"
                                    '''
                                    sh '''
                                        docker run --rm --user 0 --volumes-from jenkins \
                                          --network "$DOCKER_NETWORK" \
                                          "$COSIGN_IMAGE" \
                                          verify-attestation \
                                          --type cyclonedx \
                                          --allow-http-registry --allow-insecure-registry \
                                          --registry-username "$NEXUS_USER" \
                                          --registry-password "$NEXUS_PASS" \
                                          --key "$COSIGN_PUB" \
                                          "$imageRef" > "$SBOM_ATTESTATION"
                                    '''
                                }
                            }
                        }
                    }
                }
            }

            stage('Kubernetes') {
                when { expression { return env.DEPLOY == 'true' } }
                steps {
                    script {
                        explainAndFail(
                            'Kubernetes',
                            "Apply ou rollout falhou. Confira ${env.CRED_KUBECONFIG}, o manifesto k8s/ e se o cluster puxa ${env.REGISTRY_PULL}."
                        ) {
                            def kubeArgs = [credentialsId: env.CRED_KUBECONFIG]
                            if (env.K8S_SERVER?.trim()) {
                                kubeArgs.serverUrl = env.K8S_SERVER
                            }
                            withKubeConfig(kubeArgs) {
                                withCredentials([
                                    usernamePassword(credentialsId: env.CRED_NEXUS, usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')
                                ]) {
                                    dir(env.branch) {
                                        sh '''
                                            test -f "$k8sDir/namespace.yaml"
                                            kubectl apply -f "$k8sDir/namespace.yaml"
                                            NS="$(kubectl get -f "$k8sDir/namespace.yaml" -o jsonpath='{.metadata.name}')"
                                            kubectl -n "$NS" create secret docker-registry nexus-registry \
                                              --docker-server="$REGISTRY_PULL" \
                                              --docker-username="$NEXUS_USER" \
                                              --docker-password="$NEXUS_PASS" \
                                              --dry-run=client -o yaml | kubectl apply -f -
                                        '''
                                        sh "sed 's|PLACEHOLDER_IMAGE|${env.k8sImage}|g' '${env.k8sDir}/deployment.yaml' | kubectl apply -f -"
                                        sh '''
                                            kubectl apply -f "$k8sDir/service.yaml"
                                            NS="$(kubectl get -f "$k8sDir/namespace.yaml" -o jsonpath='{.metadata.name}')"
                                            kubectl -n "$NS" wait --for=condition=available --timeout=180s --all deploy
                                            kubectl -n "$NS" get pods,svc
                                        '''
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    recordLeadTime()
                }
                archiveArtifacts artifacts: 'reports/*.json', allowEmptyArchive: true
                script {
                    def toEmail = env.commitEmail ?: env.commit_email
                    if (!toEmail?.contains('@')) {
                        echo 'No commit author email; skipping notification'
                        return
                    }
                    def errorHtml = env.PIPELINE_ERROR
                        ? "<p><b>Erro:</b> ${env.PIPELINE_ERROR}</p>"
                        : ''
                    def leadHtml = env.LEAD_TIME_SECONDS
                        ? "<p><b>Lead time:</b> ${env.LEAD_TIME_SECONDS}s</p>"
                        : ''
                    emailext(
                        to: toEmail,
                        from: env.MAIL_FROM,
                        replyTo: env.MAIL_FROM,
                        mimeType: 'text/html',
                        attachLog: true,
                        subject: "[Jenkins] ${env.repo_full_name ?: env.JOB_NAME} #${env.BUILD_NUMBER} — ${currentBuild.currentResult}",
                        body: """
                            <html>
                              <body style="font-family: sans-serif; line-height: 1.4;">
                                <h2>Build ${currentBuild.currentResult}</h2>
                                ${errorHtml}
                                ${leadHtml}
                                <p><b>Repo:</b> ${env.repo_full_name}</p>
                                <p><b>Branch:</b> ${env.branch}</p>
                                <p><b>Autor:</b> ${env.commitAuthor ?: env.commit_author} &lt;${toEmail}&gt;</p>
                                <p><b>Commit:</b> ${env.commit}</p>
                                <p><b>Imagem:</b> ${env.image}</p>
                                <p><b>Digest:</b> ${env.imageRef}</p>
                                <p><a href="${env.BUILD_URL}">Abrir build no Jenkins</a></p>
                              </body>
                            </html>
                        """
                    )
                }
                sh 'docker logout "$REGISTRY_PUSH" || true'
                sh "docker image rm -f '${env.image}' || true"
            }
        }
    }
}

def resolveEnvironment(Map config) {
    applySettings(config)
    def envs = config.environments
    if (!envs) {
        env.DEPLOY = 'true'
        env.DEPLOY_ENV = 'prod'
        return
    }
    def branch = (env.branch ?: '').trim()
    def profile = envs[branch]
    if (profile == null) {
        def match = envs.find { key, value -> key.toString() == branch }
        profile = match ? match.value : null
    }
    if (!profile) {
        env.DEPLOY = 'false'
        env.DEPLOY_ENV = ''
        echo "Branch ${branch} sem ambiente — CI apenas"
        return
    }
    applySettings(profile as Map)
    env.DEPLOY = 'true'
    env.DEPLOY_ENV = ((profile['name'] ?: branch) as String)
    echo "Ambiente ${env.DEPLOY_ENV} (branch ${branch})"
}

def applySettings(Map config) {
    [
        'GIT_INTERNAL_HOST',
        'REGISTRY_PUSH', 'REGISTRY_PULL', 'REGISTRY_REPO',
        'K8S_SERVER', 'DOCKER_NETWORK', 'MAIL_FROM', 'TRIVY_CACHE_ROOT',
        'CRED_GIT_SSH', 'CRED_NEXUS', 'CRED_COSIGN_KEY', 'CRED_COSIGN_PUB',
        'CRED_COSIGN_PASSWORD', 'CRED_KUBECONFIG'
    ].each { key ->
        if (config.containsKey(key) && config[key] != null) {
            env[key] = config[key].toString()
        }
    }
}

def bindWebhookParams() {
    if (params.ref) { env.ref = params.ref }
    if (params.branch) { env.branch = params.branch }
    if (params.repo_full_name) { env.repo_full_name = params.repo_full_name }
    if (params.ssh_url) { env.ssh_url = params.ssh_url }
    if (params.html_url) { env.html_url = params.html_url }
    if (params.after) { env.after = params.after }
    if (params.commit_email) { env.commit_email = params.commit_email }
    if (params.commit_author) { env.commit_author = params.commit_author }
    if (params.commit_timestamp) { env.commit_timestamp = params.commit_timestamp }
}

def recordLeadTime() {
    def raw = env.commit_timestamp ?: ''
    if (!raw) {
        return
    }
    try {
        def instant
        try {
            instant = java.time.Instant.parse(raw)
        } catch (Exception ignored) {
            instant = java.time.OffsetDateTime.parse(raw).toInstant()
        }
        def seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
        env.LEAD_TIME_SECONDS = "${seconds}"
        echo "Lead time for changes: ${seconds}s (commit ${raw} → agora)"
        def desc = currentBuild.description ?: ''
        if (!desc.contains('LT ')) {
            currentBuild.description = desc ? "${desc} · LT ${seconds}s" : "LT ${seconds}s"
        }
    } catch (Exception e) {
        echo "Lead time: timestamp inválido (${raw}): ${e.message}"
    }
}

def explainAndFail(String title, String hint, Closure body) {
    try {
        body()
    } catch (err) {
        def detail = err.getMessage() ?: err.toString()
        env.PIPELINE_ERROR = "${title}: ${detail}"
        echo """
============================================================
FALHOU: ${title}
${hint}
Detalhe: ${detail}
============================================================
"""
        error("${title}: ${detail}")
    }
}

def recordFailure(String envKey, String title, String hint, Closure body) {
    try {
        body()
    } catch (err) {
        def detail = err.getMessage() ?: err.toString()
        def msg = "${title}: ${detail}"
        if (envKey == 'GITLEAKS_ERROR') {
            env.GITLEAKS_ERROR = msg
        } else if (envKey == 'SEMGREP_ERROR') {
            env.SEMGREP_ERROR = msg
        } else if (envKey == 'TRIVYFS_ERROR') {
            env.TRIVYFS_ERROR = msg
        } else if (envKey == 'TRIVYSBOM_ERROR') {
            env.TRIVYSBOM_ERROR = msg
        }
        env.PIPELINE_ERROR = [env.PIPELINE_ERROR, msg].findAll { it }.join(' | ')
        echo """
============================================================
FALHOU: ${title}
${hint}
Detalhe: ${detail}
============================================================
"""
        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
            error(msg)
        }
    }
}
