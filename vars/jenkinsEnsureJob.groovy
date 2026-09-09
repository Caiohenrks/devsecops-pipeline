def call(Map config = [:]) {
    def folder = (config.folder ?: 'services') as String
    def name = (config.name ?: '') as String
    def displayName = (config.displayName ?: name) as String
    def credentialsId = (config.credentialsId ?: 'jenkins-api') as String
    def jenkinsUrl = ((config.jenkinsUrl ?: env.JENKINS_URL ?: 'http://127.0.0.1:8080') as String).replaceAll('/+$', '')
    def scmUrl = giteaSshUrl(config.scmUrl ?: env.ssh_url ?: '')
    def scmCredentialsId = (config.scmCredentialsId ?: 'gitea-ssh') as String
    def scmBranch = (config.scmBranch ?: env.branch ?: 'main') as String
    def scriptPath = (config.scriptPath ?: 'Jenkinsfile') as String

    if (!name?.trim()) {
        error('jenkinsEnsureJob: name é obrigatório')
    }
    if (!scmUrl?.trim()) {
        error('jenkinsEnsureJob: scmUrl é obrigatório (SSH do Gitea)')
    }

    writeFile file: 'folder.xml', text: '''\
<com.cloudbees.hudson.plugins.folder.Folder>
  <description>Jobs por microserviço (um histórico / uma métrica cada)</description>
  <properties/>
</com.cloudbees.hudson.plugins.folder.Folder>
'''.stripIndent()

    writeFile file: 'job.xml', text: """\
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition>
  <displayName>${xmlEscape(displayName)}</displayName>
  <keepDependencies>false</keepDependencies>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        ${stringParamXml('ref', 'Git ref')}
        ${stringParamXml('branch', 'Branch')}
        ${stringParamXml('repo_full_name', 'owner/repo')}
        ${stringParamXml('ssh_url', 'Git SSH URL')}
        ${stringParamXml('html_url', 'Repo HTML URL')}
        ${stringParamXml('after', 'Commit SHA')}
        ${stringParamXml('commit_email', 'Author email')}
        ${stringParamXml('commit_author', 'Author name')}
        ${stringParamXml('commit_timestamp', 'Commit timestamp')}
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition">
    <scm class="hudson.plugins.git.GitSCM">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>${xmlEscape(scmUrl)}</url>
          <credentialsId>${xmlEscape(scmCredentialsId)}</credentialsId>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/${xmlEscape(scmBranch)}</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions/>
    </scm>
    <scriptPath>${xmlEscape(scriptPath)}</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <disabled>false</disabled>
</flow-definition>
""".stripIndent()

    withCredentials([usernamePassword(
        credentialsId: credentialsId,
        usernameVariable: 'JENKINS_API_USER',
        passwordVariable: 'JENKINS_API_TOKEN'
    )]) {
        withEnv([
            "JENKINS_API_URL=${jenkinsUrl}",
            "JENKINS_JOB_FOLDER=${folder}",
            "JENKINS_JOB_NAME=${name}"
        ]) {
            sh '''
                set -eu
                crumb() {
                    curl -sf -u "$JENKINS_API_USER:$JENKINS_API_TOKEN" \
                      "$JENKINS_API_URL/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,%22:%22,//crumb)"
                }
                status() {
                    curl -s -o /dev/null -w "%{http_code}" -u "$JENKINS_API_USER:$JENKINS_API_TOKEN" "$1" || true
                }
                put_xml() {
                    code=$(curl -s -o /tmp/jenkins-api.out -w "%{http_code}" \
                      -u "$JENKINS_API_USER:$JENKINS_API_TOKEN" \
                      -H "$(crumb)" -H "Content-Type: application/xml" \
                      --data-binary @"$1" $2 "$3" || true)
                    case "$code" in
                      200|201|204|409) echo "HTTP $code $3" ;;
                      *) echo "HTTP $code $3"; cat /tmp/jenkins-api.out; exit 1 ;;
                    esac
                }

                folder_url="$JENKINS_API_URL/job/$JENKINS_JOB_FOLDER"
                job_url="$folder_url/job/$JENKINS_JOB_NAME"

                if [ "$(status "$folder_url/api/json")" = "404" ]; then
                    echo "Criando pasta $JENKINS_JOB_FOLDER"
                    put_xml folder.xml "" "$JENKINS_API_URL/createItem?name=$JENKINS_JOB_FOLDER"
                fi

                if [ "$(status "$job_url/api/json")" = "404" ]; then
                    echo "Criando job $JENKINS_JOB_FOLDER/$JENKINS_JOB_NAME"
                    put_xml job.xml "" "$folder_url/createItem?name=$JENKINS_JOB_NAME"
                else
                    echo "Atualizando job $JENKINS_JOB_FOLDER/$JENKINS_JOB_NAME"
                    put_xml job.xml "-X POST" "$job_url/config.xml"
                fi
            '''
        }
    }

    echo "Job ${folder}/${name} → SCM ${scmUrl} (${scriptPath} @ */${scmBranch})"
    return "${folder}/${name}"
}

def giteaSshUrl(String raw) {
    def url = (raw ?: '').trim()
    if (!url) {
        return ''
    }
    def ssh = (url =~ /^ssh:\/\/git@[^\/:]+(?::\d+)?\/(.+)$/)
    if (ssh.matches()) {
        return "git@gitea:${ssh[0][1]}"
    }
    return url.replaceFirst(/git@[^:]+:/, 'git@gitea:')
}

def xmlEscape(String value) {
    (value ?: '')
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
}

def stringParamXml(String name, String description) {
    """
        <hudson.model.StringParameterDefinition>
          <name>${name}</name>
          <description>${description}</description>
          <defaultValue></defaultValue>
          <trim>true</trim>
        </hudson.model.StringParameterDefinition>
    """
}
