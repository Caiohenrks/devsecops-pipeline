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
            def jobName = job['name']
            def buildsJson = sh(
                returnStdout: true,
                script: """
                    curl -sf -g -u "\$JENKINS_API_USER:\$JENKINS_API_TOKEN" \
                      '${jenkinsUrl}/job/${folder}/job/${jobName}/api/json?tree=builds[number,result,timestamp,duration,description]{0,80}'
                """
            ).trim()
            def builds = (parseJson(buildsJson).builds ?: []).findAll { b ->
                ((b['timestamp'] ?: 0L) as long) >= cutoff && b['result'] in ['SUCCESS', 'FAILURE', 'UNSTABLE']
            }
            services << summarize(job, builds, days, slo, jenkinsUrl)
        }
    }

    renderHtml(services, days, slo, jenkinsUrl)
}

def summarize(job, List builds, int days, double slo, String jenkinsUrl) {
    def success = builds.findAll { it['result'] == 'SUCCESS' }
    def failed = builds.findAll { it['result'] == 'FAILURE' }
    def done = success.size() + failed.size()
    def leads = success.collect { leadSeconds(it['description'] as String) }.findAll { it != null }
    def leadAvg = leads ? (leads.sum() / leads.size()) : null
    def freqWeek = days > 0 ? (success.size() / (days / 7.0d)) : 0d
    def cfr = done ? (failed.size() / (done as double)) : null
    def ttr = meanRestoreSeconds(builds)
    def failRate = done ? (failed.size() / (done as double)) : 0d
    def budget = slo < 1d ? Math.min(1d, failRate / (1d - slo)) : 0d
    def jobName = (job['name'] ?: '') as String
    def repo = (job['displayName'] ?: jobName) as String
    def runbook = hasRunbook(repo)

    [
        name       : jobName,
        display    : repo,
        url        : (job['url'] ?: "${jenkinsUrl}/job/services/job/${jobName}/") as String,
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
    def ordered = builds.sort { a, b -> ((a['timestamp'] ?: 0L) as long) <=> ((b['timestamp'] ?: 0L) as long) }
    def waits = []
    for (int i = 0; i < ordered.size(); i++) {
        if (ordered[i]['result'] != 'FAILURE') {
            continue
        }
        def failTs = (ordered[i]['timestamp'] ?: 0L) as long
        def next = ordered.find { ((it['timestamp'] ?: 0L) as long) > failTs && it['result'] == 'SUCCESS' }
        if (next) {
            waits << ((((next['timestamp'] ?: 0L) as long) - failTs) / 1000.0d)
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

@NonCPS
def renderHtml(List services, int days, double slo, String jenkinsUrl) {
    def n = services.size() ?: 1
    def leadVals = services.collect { it['leadAvg'] }.findAll { it != null }
    def ttrVals = services.collect { it['ttr'] }.findAll { it != null }
    def cfrVals = services.collect { it['cfr'] }.findAll { it != null }
    def lead = leadVals ? (leadVals.sum() / leadVals.size()) : null
    def freqVals = services.collect { it['freqWeek'] ?: 0d }
    def freq = freqVals ? (freqVals.sum() / n) : 0d
    def cfr = cfrVals ? (cfrVals.sum() / cfrVals.size()) : null
    def ttr = ttrVals ? (ttrVals.sum() / ttrVals.size()) : null
    def budgetVals = services.collect { it['budget'] ?: 0d }
    def budget = budgetVals ? (budgetVals.sum() / n) : 0d
    def runbooks = services.count { it['runbook'] } / (services.size() ?: 1)

    def generated = java.time.Instant.now().toString()
    def cards = [
        card('Lead time for changes', fmtDuration(lead), 'Tempo médio entre o commit (webhook) e o fim do job de sucesso. Vem do LT gravado na description.'),
        card('Deployment frequency', String.format(Locale.US, '%.1f / semana', freq), 'Deploys = builds SUCCESS do job do serviço (passou o gate e chegou ao fim).'),
        card('Change failure rate', fmtPct(cfr), 'FAILURE / (SUCCESS + FAILURE) no job do serviço. Scan que barra conta como falha da mudança.'),
        card('Time to restore', fmtDuration(ttr), 'Média entre um FAILURE e o próximo SUCCESS daquele serviço.'),
        card('Error budget consumption', fmtPct(budget), "SLO ${fmtPct(slo)} de sucesso. 100% = o orçamento do período acabou."),
        card('Cobertura de runbooks', fmtPct(runbooks), 'Serviços com docs/runbook.md no Gitea (main).')
    ].join('\n')

    def rows = services.sort { a, b -> (a['display'] ?: '') <=> (b['display'] ?: '') }.collect { s ->
        def name = (s['display'] ?: '') as String
        def shortName = name.contains('/') ? name.split('/')[-1] : name
        """<tr>
          <td>
            <a class="svc" href="${esc(s['url'])}">
              <span class="dot"></span>
              <span>
                <strong>${esc(shortName)}</strong>
                <small>${esc(name)}</small>
              </span>
            </a>
          </td>
          <td><span class="pill">${s['success']}<em>/${s['builds']}</em></span></td>
          <td>${esc(fmtDuration(s['leadAvg']))}</td>
          <td>${String.format(Locale.US, '%.1f', s['freqWeek'])}</td>
          <td>${badge(fmtPct(s['cfr']))}</td>
          <td>${esc(fmtDuration(s['ttr']))}</td>
          <td>${badge(fmtPct(s['budget']))}</td>
          <td>${badge(s['runbook'] ? 'sim' : 'não')}</td>
        </tr>"""
    }.join('\n')

    """<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>DORA / continuidade</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f8f8f8;
      --card: #ffffff;
      --ink: #333333;
      --muted: #4d545d;
      --line: #d8d8d8;
      --accent: #1a7bb9;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      color: var(--ink);
      background: var(--bg);
      font: 14px/1.5 system-ui, "Segoe UI", roboto, "Noto Sans", sans-serif;
    }
    main { max-width: 1120px; margin: 0 auto; padding: 28px 20px 48px; }
    header {
      display: flex;
      justify-content: space-between;
      gap: 20px;
      align-items: flex-end;
      margin-bottom: 20px;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--line);
    }
    .kicker {
      margin: 0 0 6px;
      color: var(--muted);
      font-size: 0.72rem;
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }
    h1 { margin: 0; font-size: 1.6rem; font-weight: 600; }
    .sub { margin: 8px 0 0; color: var(--muted); max-width: 52ch; }
    .meta {
      text-align: right;
      color: var(--muted);
      font-size: 0.82rem;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
      margin-bottom: 20px;
    }
    article {
      padding: 14px 16px;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--card);
    }
    article h2 {
      margin: 0 0 10px;
      color: var(--muted);
      font-size: 0.72rem;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    article .v {
      margin: 0 0 8px;
      font-size: 1.55rem;
      font-weight: 600;
      color: var(--ink);
    }
    article p { margin: 0; color: var(--muted); font-size: 0.8rem; }
    .panel {
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--card);
      overflow: hidden;
    }
    .panel h2 {
      margin: 0;
      padding: 14px 16px 0;
      font-size: 1rem;
      font-weight: 600;
    }
    .scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; min-width: 820px; }
    th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid var(--line); }
    th {
      color: var(--muted);
      font-size: 0.72rem;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    tbody tr:hover { background: #f4f4f4; }
    .svc {
      display: flex;
      align-items: center;
      gap: 10px;
      color: inherit;
      text-decoration: none;
    }
    .svc:hover strong { color: var(--accent); }
    .svc strong { display: block; font-weight: 600; }
    .svc small { color: var(--muted); }
    .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #9ba7af;
    }
    .pill, .badge {
      display: inline-flex;
      align-items: center;
      border: 1px solid var(--line);
      border-radius: 3px;
      background: #f4f4f4;
      color: var(--ink);
      font-size: 0.78rem;
      font-weight: 600;
    }
    .pill { padding: 2px 8px; }
    .pill em { font-style: normal; color: var(--muted); }
    .badge { padding: 2px 7px; }
    footer { margin-top: 16px; color: var(--muted); font-size: 0.8rem; }
    code {
      padding: 1px 5px;
      border: 1px solid var(--line);
      border-radius: 3px;
      background: #f4f4f4;
      font-family: ui-monospace, "SF Mono", consolas, monospace;
      font-size: 0.86em;
    }
    a { color: var(--accent); }
    @media (max-width: 860px) {
      header { display: block; }
      .meta { text-align: left; margin-top: 12px; }
      .grid { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <main>
    <header>
      <div>
        <p class="kicker">Continuidade · lab DevSecOps</p>
        <h1>DORA metrics</h1>
        <p class="sub">Janela de ${days} dias. O job <a href="${esc(jenkinsUrl)}/job/metrics/">metrics</a> só lê o histórico em <code>services/</code> — não entra no webhook nem na pipeline.</p>
      </div>
      <div class="meta">
        <div>SLO ${esc(fmtPct(slo))}</div>
        <div>${esc(generated)}</div>
      </div>
    </header>
    <div class="grid">
      ${cards}
    </div>
    <section class="panel">
      <h2>Por serviço</h2>
      <div class="scroll">
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
      </div>
    </section>
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

@NonCPS
def card(String title, String value, String hint) {
    """<article>
      <h2>${esc(title)}</h2>
      <p class="v">${esc(value)}</p>
      <p>${esc(hint)}</p>
    </article>"""
}

@NonCPS
def badge(String text) {
    """<span class="badge">${esc(text)}</span>"""
}

@NonCPS
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

@NonCPS
def fmtPct(Object ratio) {
    if (ratio == null) {
        return '—'
    }
    return String.format(Locale.US, '%.0f%%', (ratio as double) * 100d)
}

@NonCPS
def esc(Object value) {
    (value ?: '')
        .toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
}
