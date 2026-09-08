import com.cloudbees.hudson.plugins.folder.Folder
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

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
                token: 'build-and-push',
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
                            error('repo_full_name ausente no webhook. O dispatcher não sabe qual job criar.')
                        }

                        def skip = (config.skipRepos ?: ['admin/devsecops-pipeline', 'admin/curso']) as List
                        def repoName = repo.contains('/') ? repo.split('/')[-1] : repo
                        if (skip.contains(repo) || skip.contains(repoName)) {
                            echo "Ignorando ${repo} — não é microserviço."
                            currentBuild.description = "skip ${repo}"
                            return
                        }

                        def jobName = ensureServiceJob(repo)
                        currentBuild.description = "dispatch ${jobName}"
                        echo "Disparando ${jobName}"

                        build job: jobName, wait: true, propagate: true, parameters: [
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

@NonCPS
String ensureServiceJob(String repoFullName) {
    def jobName = repoFullName.replaceAll('[^A-Za-z0-9_.-]', '-')
    def jenkins = Jenkins.get()
    def fullName

    synchronized (jenkins) {
        def folder = jenkins.getItem('services')
        if (folder == null) {
            folder = jenkins.createProject(Folder, 'services')
        }

        def job = folder.getItem(jobName)
        if (job == null) {
            job = folder.createProject(WorkflowJob, jobName)
        }

        job.setDisplayName(repoFullName)
        job.setDefinition(new CpsFlowDefinition('''\
@Library('devsecops') _

devsecopsPipeline()
'''.stripIndent(), true))

        job.removeProperty(ParametersDefinitionProperty)
        job.addProperty(new ParametersDefinitionProperty(
            new StringParameterDefinition('ref', '', 'Git ref (refs/heads/...)'),
            new StringParameterDefinition('branch', '', 'Branch'),
            new StringParameterDefinition('repo_full_name', repoFullName, 'owner/repo'),
            new StringParameterDefinition('ssh_url', '', 'Git SSH URL'),
            new StringParameterDefinition('html_url', '', 'Repo HTML URL'),
            new StringParameterDefinition('after', '', 'Commit SHA'),
            new StringParameterDefinition('commit_email', '', 'Author email'),
            new StringParameterDefinition('commit_author', '', 'Author name'),
            new StringParameterDefinition('commit_timestamp', '', 'Commit timestamp (lead time)')
        ))
        job.save()
        fullName = "services/${jobName}"
    }

    return fullName
}
