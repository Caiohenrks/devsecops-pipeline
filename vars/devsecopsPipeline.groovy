def call(Map config = [:]) {
    pipeline {
        agent any

        environment {
            GITLEAKS_IMAGE = 'ghcr.io/gitleaks/gitleaks:v8.28.0'
            SEMGREP_IMAGE  = 'semgrep/semgrep:1.128.0'
            TRIVY_IMAGE    = 'aquasec/trivy:0.74.0'
            COSIGN_IMAGE   = 'cgr.dev/chainguard/cosign:latest@sha256:2af5cabe038577e02b21be3b6e2622c2cd2659dcdef2bdc0fbf5f64e16965a88'
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'branch', value: '$.ref', regexpFilter: 'refs/heads/'],
                    [key: 'repo_full_name', value: '$.repository.full_name'],
                    [key: 'ssh_url', value: '$.repository.ssh_url'],
                    [key: 'html_url', value: '$.repository.html_url'],
                    [key: 'after', value: '$.after'],
                    [key: 'commit_email', value: '$.head_commit.author.email'],
                    [key: 'commit_author', value: '$.head_commit.author.name']
                ],
                token: 'build-and-push',
                causeString: 'Gitea push on $repo_full_name ($branch)',
                printContributedVariables: true,
                printPostContent: true,
                silentResponse: false,
                regexpFilterText: '$ref',
                regexpFilterExpression: '^refs/heads/.+$'
            )
        }

        stages {
            stage('Prepare environment') {
                steps {
                    script {
                        cleanWs()
                        env.k8sDir = (config.k8sDir ?: 'k8s') as String
                        env.dockerContext = (config.context ?: '.') as String
                        env.dockerfile = (config.dockerfile ?: 'Dockerfile') as String
                        env.sshUrl = (env.ssh_url ?: '').replace('localhost', 'gitea')
                        env.htmlUrl = (env.html_url ?: '').replace('http://localhost:8082', 'http://gitea:3000')
                        env.registry = '127.0.0.1:5000/docker'
                        env.srcDir = "${env.WORKSPACE}/${env.branch}"
                        env.reportsDir = "${env.WORKSPACE}/reports"
                        currentBuild.description = env.repo_full_name ?: 'unknown-repo'
                        sh "mkdir -p '${env.reportsDir}'"
                    }
                }
            }

            stage('Clone repository') {
                steps {
                    script {
                        def gitRef = (env.after ?: '').trim() ? env.after : "*/${env.branch}"
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: gitRef]],
                            extensions: [
                                [$class: 'RelativeTargetDirectory', relativeTargetDir: env.branch]
                            ],
                            userRemoteConfigs: [[
                                credentialsId: 'gitea-ssh',
                                url: env.sshUrl
                            ]]
                        ])
                        env.commit = env.after ?: sh(returnStdout: true, script: "git -C '${env.srcDir}' rev-parse HEAD").trim()
                        env.commitEmail = env.commit_email ?: sh(returnStdout: true, script: "git -C '${env.srcDir}' log -1 --pretty=%ae").trim()
                        env.commitAuthor = env.commit_author ?: sh(returnStdout: true, script: "git -C '${env.srcDir}' log -1 --pretty=%an").trim()
                        env.shortSha = env.commit.substring(0, 12)
                        env.image = "${env.registry}/${env.repo_full_name}:${env.shortSha}"
                        env.k8sImage = "nexus:8082/docker/${env.repo_full_name}:${env.shortSha}"
                        echo "Commit ${env.commit}"
                        echo "Image ${env.image}"
                        echo "K8s image ${env.k8sImage}"
                        echo "Notify ${env.commitAuthor} <${env.commitEmail}>"
                        currentBuild.description = "${env.repo_full_name} @ ${env.shortSha}"
                    }
                }
            }

            stage('Security scans') {
                parallel {
                    stage('Gitleaks') {
                        steps {
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

                    stage('Semgrep') {
                        steps {
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  --entrypoint sh \
                                  ${env.SEMGREP_IMAGE} \
                                  -c 'ln -sfn "${env.srcDir}" /src && semgrep scan --config auto --json-output="${env.reportsDir}/semgrep.json" /src'
                            """
                        }
                    }

                    stage('Trivy FS') {
                        steps {
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v trivy-cache:/root/.cache \
                                  -w '${env.srcDir}' \
                                  ${env.TRIVY_IMAGE} \
                                  fs --exit-code 0 .
                            """
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v trivy-cache:/root/.cache \
                                  -w '${env.srcDir}' \
                                  ${env.TRIVY_IMAGE} \
                                  fs --exit-code 0 --format json --output '${env.reportsDir}/trivy-fs.json' .
                            """
                            sh """
                                docker run --rm --volumes-from jenkins \
                                  -v trivy-cache:/root/.cache \
                                  -w '${env.srcDir}' \
                                  ${env.TRIVY_IMAGE} \
                                  fs --quiet --exit-code 1 --severity HIGH,CRITICAL .
                            """
                        }
                    }
                }
            }

            stage('Build image') {
                steps {
                    dir(env.branch) {
                        sh "docker build -f '${env.dockerfile}' -t '${env.image}' '${env.dockerContext}'"
                    }
                }
            }

            stage('Trivy image') {
                steps {
                    sh """
                        docker run --rm \
                          -v /var/run/docker.sock:/var/run/docker.sock \
                          -v trivy-cache:/root/.cache \
                          ${env.TRIVY_IMAGE} \
                          image --exit-code 0 '${env.image}'
                    """
                    sh """
                        docker run --rm --volumes-from jenkins \
                          -v /var/run/docker.sock:/var/run/docker.sock \
                          -v trivy-cache:/root/.cache \
                          ${env.TRIVY_IMAGE} \
                          image --exit-code 0 --format json --output '${env.reportsDir}/trivy-image.json' '${env.image}'
                    """
                    sh """
                        docker run --rm \
                          -v /var/run/docker.sock:/var/run/docker.sock \
                          -v trivy-cache:/root/.cache \
                          ${env.TRIVY_IMAGE} \
                          image --quiet --exit-code 1 --severity HIGH,CRITICAL '${env.image}'
                    """
                }
            }

            stage('Push image') {
                steps {
                    withCredentials([
                        usernamePassword(credentialsId: 'nexus-account', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS'),
                        file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY'),
                        file(credentialsId: 'cosign-pub', variable: 'COSIGN_PUB'),
                        string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')
                    ]) {
                        sh 'echo "$NEXUS_PASS" | docker login 127.0.0.1:5000 -u "$NEXUS_USER" --password-stdin'
                        sh "docker push '${env.image}'"
                        script {
                            def digest = sh(
                                returnStdout: true,
                                script: "docker inspect --format='{{index .RepoDigests 0}}' '${env.image}'"
                            ).trim()
                            env.imageRef = digest.replaceFirst('127.0.0.1:5000', 'nexus:8082')
                            echo "Signing ${env.imageRef}"
                        }
                        sh '''
                            docker run --rm --user 0 --volumes-from jenkins \
                              --network infra_devsecops-network \
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
                              --network infra_devsecops-network \
                              "$COSIGN_IMAGE" \
                              verify \
                              --allow-http-registry --allow-insecure-registry \
                              --registry-username "$NEXUS_USER" \
                              --registry-password "$NEXUS_PASS" \
                              --key "$COSIGN_PUB" \
                              "$imageRef"
                        '''
                    }
                }
            }

            stage('Kubernetes') {
                steps {
                    withKubeConfig(
                        credentialsId: 'k3s-kubeconfig',
                        serverUrl: 'https://host.docker.internal:6443'
                    ) {
                        withCredentials([
                            usernamePassword(credentialsId: 'nexus-account', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')
                        ]) {
                            dir(env.branch) {
                                sh '''
                                    test -f "$k8sDir/namespace.yaml"
                                    kubectl apply -f "$k8sDir/namespace.yaml"
                                    NS="$(kubectl get -f "$k8sDir/namespace.yaml" -o jsonpath='{.metadata.name}')"
                                    kubectl -n "$NS" create secret docker-registry nexus-registry \
                                      --docker-server=nexus:8082 \
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

        post {
            always {
                archiveArtifacts artifacts: 'reports/**', allowEmptyArchive: true
                script {
                    def toEmail = env.commitEmail ?: env.commit_email
                    if (!toEmail?.contains('@')) {
                        echo 'No commit author email; skipping notification'
                        return
                    }
                    emailext(
                        to: toEmail,
                        from: 'contato@henrks.com',
                        replyTo: 'contato@henrks.com',
                        mimeType: 'text/html',
                        attachLog: true,
                        subject: "[Jenkins] ${env.repo_full_name ?: env.JOB_NAME} #${env.BUILD_NUMBER} — ${currentBuild.currentResult}",
                        body: """
                            <html>
                              <body style="font-family: sans-serif; line-height: 1.4;">
                                <h2>Build ${currentBuild.currentResult}</h2>
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
                sh 'docker logout 127.0.0.1:5000 || true'
                sh "docker image rm -f '${env.image}' || true"
            }
        }
    }
}
