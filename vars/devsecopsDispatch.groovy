def call(Map config = [:]) {
    pipeline {
        agent any

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
                    [key: 'commit_author', value: '$.head_commit.author.name'],
                    [key: 'commit_timestamp', value: '$.head_commit.timestamp']
                ],
                token: 'dispatch',
                causeString: 'Dispatch $repo_full_name ($branch)',
                printContributedVariables: true,
                printPostContent: true,
                silentResponse: false,
                regexpFilterText: '$ref',
                regexpFilterExpression: '^refs/heads/.+$'
            )
        }

        stages {
            stage('Dispatch') {
                steps {
                    script {
                        def repo = (env.repo_full_name ?: '').trim()
                        if (!repo) {
                            echo 'Sem payload de webhook. Trigger registrado. Um push em um serviço cria o job.'
                            currentBuild.description = 'trigger registered'
                            return
                        }

                        def skip = (config.skipRepos ?: ['admin/devsecops-pipeline', 'admin/curso']) as List
                        def repoName = repo.contains('/') ? repo.split('/')[-1] : repo
                        if (skip.contains(repo) || skip.contains(repoName)) {
                            echo "Ignorando ${repo} — não é microserviço."
                            currentBuild.description = "skip ${repo}"
                            return
                        }

                        def jobName = repo.replaceAll('[^A-Za-z0-9_.-]', '-')
                        def fullName = jenkinsEnsureJob(
                            folder: 'services',
                            name: jobName,
                            displayName: repo,
                            credentialsId: config.jenkinsCredentialsId ?: 'jenkins-api',
                            scmUrl: env.ssh_url,
                            scmCredentialsId: config.scmCredentialsId ?: 'gitea-ssh',
                            scmBranch: env.branch ?: 'main'
                        )
                        currentBuild.description = "dispatch ${fullName}"
                        echo "Disparando ${fullName}"

                        build job: fullName, wait: false, parameters: [
                            string(name: 'ref', value: env.ref ?: ''),
                            string(name: 'branch', value: env.branch ?: ''),
                            string(name: 'repo_full_name', value: repo),
                            string(name: 'ssh_url', value: env.ssh_url ?: ''),
                            string(name: 'html_url', value: env.html_url ?: ''),
                            string(name: 'after', value: env.after ?: ''),
                            string(name: 'commit_email', value: env.commit_email ?: ''),
                            string(name: 'commit_author', value: env.commit_author ?: ''),
                            string(name: 'commit_timestamp', value: env.commit_timestamp ?: '')
                        ]
                    }
                }
            }
        }
    }
}
