def call(Map config = [:]) {
    pipeline {
        agent any

        triggers {
            cron('H/30 * * * *')
            GenericTrigger(
                token: 'dora',
                causeString: 'DORA refresh',
                silentResponse: false,
                regexpFilterText: '',
                regexpFilterExpression: ''
            )
        }

        stages {
            stage('DORA') {
                steps {
                    script {
                        def days = (config.windowDays ?: 30) as int
                        def slo = (config.slo ?: 0.99d) as double
                        def folder = (config.folder ?: 'services') as String
                        def page = collectAndRender(folder, days, slo)
                        writeFile file: 'dora.html', text: page
                        sh '''
                            mkdir -p /var/jenkins_home/userContent/dora
                            cp dora.html /var/jenkins_home/userContent/dora/index.html
                        '''
                        echo "Página: ${env.JENKINS_URL}userContent/dora/"
                        currentBuild.description = "DORA ${days}d → /userContent/dora/"
                    }
                }
            }
        }
    }
}

def collectAndRender(String folder, int days, double slo) {
    def jenkinsUrl = (env.JENKINS_URL ?: 'http://127.0.0.1:8080').replaceAll('/+$', '')
    def cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
    def services = []

    withCredentials([usernamePassword(
        credentialsId: 'jenkins-api',
        usernameVariable: 'JENKINS_API_USER',
        passwordVariable: 'JENKINS_API_TOKEN'
    )]) {
        def jobsJson = sh(
            returnStdout: true,
            script: """
                curl -sf -g -u "\$JENKINS_API_USER:\$JENKINS_API_TOKEN" \
                  '${jenkinsUrl}/job/${folder}/api/json?tree=jobs[name,displayName,url]'
            """
        ).trim()
        def jobs = parseJson(jobsJson).jobs ?: []
        jobs.each { job ->
            def buildsJson = sh(
                returnStdout: true,
                script: """
                    curl -sf -g -u "\$JENKINS_API_USER:\$JENKINS_API_TOKEN" \
                      '${jenkinsUrl}/job/${folder}/job/${job.name}/api/json?tree=builds[number,result,timestamp,duration,description]{0,80}'
                """
            ).trim()
            def builds = (parseJson(buildsJson).builds ?: []).findAll { b ->
                (b.timestamp ?: 0L) >= cutoff && b.result in ['SUCCESS', 'FAILURE', 'UNSTABLE']
            }
            services << summarize(job, builds, days, slo, jenkinsUrl)
        }
    }

    renderHtml(services, days, slo, jenkinsUrl)
}

def summarize(job, List builds, int days, double slo, String jenkinsUrl) {
    def success = builds.findAll { it.result == 'SUCCESS' }
    def failed = builds.findAll { it.result == 'FAILURE' }
    def done = success.size() + failed.size()
    def leads = success.collect { leadSeconds(it.description) }.findAll { it != null }
    def leadAvg = leads ? (leads.sum() / leads.size()) : null
    def freqWeek = days > 0 ? (success.size() / (days / 7.0d)) : 0d
    def cfr = done ? (failed.size() / (done as double)) : null
    def ttr = meanRestoreSeconds(builds)
    def failRate = done ? (failed.size() / (done as double)) : 0d
    def budget = slo < 1d ? Math.min(1d, failRate / (1d - slo)) : 0d
    def repo = (job.displayName ?: job.name) as String
    def runbook = hasRunbook(repo)

    [
        name       : job.name,
        display    : repo,
        url        : job.url ?: "${jenkinsUrl}/job/services/job/${job.name}/",
        builds     : builds.size(),
        success    : success.size(),
        failed     : failed.size(),
        leadAvg    : leadAvg,
        freqWeek   : freqWeek,
        cfr        : cfr,
        ttr        : ttr,
        budget     : budget,
        runbook    : runbook
    ]
}

@NonCPS
def parseJson(String text) {
    new groovy.json.JsonSlurperClassic().parseText(text)
}

@NonCPS
def leadSeconds(String description) {
    def m = (description ?: '') =~ /LT\s+(\d+)s/
    m.find() ? (m.group(1) as long) : null
}

@NonCPS
def meanRestoreSeconds(List builds) {
    def ordered = builds.sort { a, b -> (a.timestamp ?: 0L) <=> (b.timestamp ?: 0L) }
    def waits = []
    for (int i = 0; i < ordered.size(); i++) {
        if (ordered[i].result != 'FAILURE') {
            continue
        }
        def next = ordered.find { it.timestamp > ordered[i].timestamp && it.result == 'SUCCESS' }
        if (next) {
            waits << ((next.timestamp - ordered[i].timestamp) / 1000.0d)
        }
    }
    waits ? (waits.sum() / waits.size()) : null
}

def hasRunbook(String repo) {
    if (!repo?.contains('/')) {
        return false
    }
    def code = sh(
        returnStdout: true,
        script: "curl -s -o /dev/null -w '%{http_code}' 'http://gitea:3000/api/v1/repos/${repo}/contents/docs/runbook.md' || true"
    ).trim()
    return code == '200'
}

def renderHtml(List services, int days, double slo, String jenkinsUrl) {
    def n = services.size() ?: 1
    def leadVals = services.collect { it.leadAvg }.findAll { it != null }
    def ttrVals = services.collect { it.ttr }.findAll { it != null }
    def cfrVals = services.collect { it.cfr }.findAll { it != null }
    def lead = leadVals ? (leadVals.sum() / leadVals.size()) : null
    def freq = services.collect { it.freqWeek }.sum() / n
    def cfr = cfrVals ? (cfrVals.sum() / cfrVals.size()) : null
    def ttr = ttrVals ? (ttrVals.sum() / ttrVals.size()) : null
    def budget = services.collect { it.budget }.sum() / n
    def runbooks = services.count { it.runbook } / (services.size() ?: 1)

    def cards = [
        card('Lead time for changes', fmtDuration(lead), 'Tempo médio entre o commit (webhook) e o fim do job de sucesso. Vem do LT gravado na description.'),
        card('Deployment frequency', String.format(Locale.US, '%.1f / semana', freq), 'Deploys = builds SUCCESS do job do serviço (passou o gate e chegou ao fim).'),
        card('Change failure rate', fmtPct(cfr), 'FAILURE / (SUCCESS + FAILURE) no job do serviço. Scan que barra conta como falha da mudança.'),
        card('Time to restore', fmtDuration(ttr), 'Média entre um FAILURE e o próximo SUCCESS daquele serviço.'),
        card('Error budget consumption', fmtPct(budget), "SLO ${fmtPct(slo)} de sucesso. 100% = o orçamento do período acabou."),
        card('Cobertura de runbooks', fmtPct(runbooks), 'Serviços com docs/runbook.md no Gitea (main).')
    ].join('\n')

    def rows = services.sort { it.display }.collect { s ->
        """<tr>
          <td><a href="${esc(s.url)}">${esc(s.display)}</a></td>
          <td>${s.success}/${s.builds}</td>
          <td>${fmtDuration(s.leadAvg)}</td>
          <td>${String.format(Locale.US, '%.1f', s.freqWeek)}</td>
          <td>${fmtPct(s.cfr)}</td>
          <td>${fmtDuration(s.ttr)}</td>
          <td>${fmtPct(s.budget)}</td>
          <td>${s.runbook ? 'sim' : 'não'}</td>
        </tr>"""
    }.join('\n')

    """<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>DORA / continuidade</title>
  <style>
    :root { color-scheme: dark; }
    body { font-family: system-ui, sans-serif; margin: 0; background: #111; color: #eee; line-height: 1.45; }
    main { max-width: 1100px; margin: 0 auto; padding: 28px 20px 48px; }
    h1 { font-size: 1.5rem; margin: 0 0 6px; }
    .sub { color: #aaa; margin: 0 0 24px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin-bottom: 28px; }
    article { background: #1c1c1c; border: 1px solid #2c2c2c; border-radius: 10px; padding: 14px 16px; }
    article h2 { font-size: 0.78rem; text-transform: uppercase; letter-spacing: .04em; color: #9ad; margin: 0 0 8px; }
    article .v { font-size: 1.55rem; font-weight: 650; margin: 0 0 8px; }
    article p { margin: 0; color: #aaa; font-size: 0.82rem; }
    table { width: 100%; border-collapse: collapse; background: #1c1c1c; border-radius: 10px; overflow: hidden; }
    th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #2c2c2c; font-size: 0.9rem; }
    th { color: #9ad; font-weight: 600; }
    a { color: #8cf; }
    footer { margin-top: 22px; color: #888; font-size: 0.82rem; }
  </style>
</head>
<body>
  <main>
    <h1>DORA / continuidade</h1>
    <p class="sub">Janela de ${days} dias · jobs em <code>services/</code> · gerado pelo job <a href="${esc(jenkinsUrl)}/job/metrics/">metrics</a> da shared library.</p>
    <div class="grid">
      ${cards}
    </div>
    <table>
      <thead>
        <tr>
          <th>Serviço</th>
          <th>OK / builds</th>
          <th>Lead time</th>
          <th>Freq / sem</th>
          <th>CFR</th>
          <th>Restore</th>
          <th>Budget</th>
          <th>Runbook</th>
        </tr>
      </thead>
      <tbody>
        ${rows ?: '<tr><td colspan="8">Nenhum job em services/.</td></tr>'}
      </tbody>
    </table>
    <footer>
      Lead time lê <code>LT Ns</code> na description do build (commit → fim do job).
      Deploy = SUCCESS. Falha = FAILURE (inclui gate de segurança).
      Restore = FAILURE → próximo SUCCESS. Runbook = <code>docs/runbook.md</code> no repo Gitea.
    </footer>
  </main>
</body>
</html>
"""
}

def card(String title, String value, String hint) {
    """<article>
      <h2>${esc(title)}</h2>
      <p class="v">${esc(value)}</p>
      <p>${esc(hint)}</p>
    </article>"""
}

def fmtDuration(Object seconds) {
    if (seconds == null) {
        return '—'
    }
    def s = seconds as double
    if (s < 60) {
        return String.format(Locale.US, '%.0fs', s)
    }
    if (s < 3600) {
        return String.format(Locale.US, '%.1f min', s / 60d)
    }
    return String.format(Locale.US, '%.1f h', s / 3600d)
}

def fmtPct(Object ratio) {
    if (ratio == null) {
        return '—'
    }
    return String.format(Locale.US, '%.0f%%', (ratio as double) * 100d)
}

def esc(Object value) {
    (value ?: '')
        .toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
}
