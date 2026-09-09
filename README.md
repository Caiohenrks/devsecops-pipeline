# Pipeline DevSecOps (shared library)

Jenkins shared library (`@Library('devsecops')`). Repo canônico no Gitea: `admin/devsecops-pipeline`. Espelho no GitHub: `Caiohenrks/devsecops-pipeline`.

O Jenkinsfile de cada microserviço tem três linhas. Scan, SBOM, build, Cosign e deploy ficam aqui.

## O que é

Dois entrypoints em `vars/`:

| Função | Job Jenkins | Papel |
|---|---|---|
| `devsecopsDispatch()` | `dispatch` | Webhook Gitea → cria/atualiza `services/<owner>-<repo>` → dispara o job do serviço (`wait: false`) |
| `devsecopsPipeline()` | `services/<owner>-<repo>` | CI/CD do microserviço |

O job do serviço é **Pipeline script from SCM** (`jenkinsEnsureJob`): clona o repo do app e executa o `Jenkinsfile` dele, que só chama a library. `admin/devsecops-pipeline` e `admin/curso` são ignorados no dispatch.

```
@Library('devsecops') _

devsecopsPipeline()
```

## O que faz

```
Push Gitea → dispatch → jenkinsEnsureJob → services/<owner>-<repo>
  → Clone → CycloneDX código → scans paralelos → Security gate
  → Build image → SBOM imagem → Trivy imagem
  → Push Nexus → Cosign sign + attest → Apply k8s
```

1. **Prepare / Clone** — payload do webhook no workspace. Imagem local `127.0.0.1:5000/docker/<repo>:<shortSha>`. No k3s a mesma tag vira `nexus:8082/docker/<repo>:<shortSha>`.
2. **CycloneDX (código)** — se existe `pom.xml`: `mvn -B -DskipTests dependency:resolve` (volume `maven-cache`) e cdxgen **sem** `--no-install-deps`. Senão cdxgen com `--no-install-deps`. Relatório: `reports/sbom-cyclonedx.json`.
3. **Scans em paralelo** — Gitleaks, Semgrep, Trivy FS (HIGH/CRITICAL), Trivy SBOM no JSON do cdxgen. Os quatro terminam. O stage **Security gate** junta as falhas e aborta.
4. **Build** → **SBOM da imagem** (`trivy image --format cyclonedx`, não cdxgen `-t docker`) → Trivy SBOM da imagem → Trivy image HIGH/CRITICAL.
5. **Push + Cosign** — `sign` → `attest --yes --type cyclonedx --predicate` → `verify` → `verify-attestation --type cyclonedx` (stdout → `reports/sbom-image-attestation.json`). Não use `cosign attach sbom` (deprecado, não assina) nem `download attestation --predicate-type cyclonedx` (o bundle novo não leva a annotation que o `download` filtra; o Nexus devolve “no attestations … https://cyclonedx.org/bom”).
6. **Kubernetes** — aplica o namespace do manifesto, cria/atualiza o secret `nexus-registry`, troca `PLACEHOLDER_IMAGE` pela tag SHA e espera o rollout.
7. **Post** — arquiva `reports/*.json`, calcula lead time, e-mail ao autor do commit, `docker logout` e remove a imagem local.

Falha de stage é **FAILURE**. O `try/catch` só registra o motivo no console (e no e-mail) antes do `error()`.

## Por que fazer

| Etapa | Risco que cobre |
|---|---|
| Gitleaks | Secret commitado no git |
| Semgrep | Falha de código (SAST) |
| Trivy FS | CVE HIGH/CRITICAL no filesystem do repo |
| CycloneDX + Trivy SBOM | CVE no grafo de dependências (Maven resolvido entra aqui) |
| Trivy image + SBOM da imagem | CVE e inventário do que de fato sobe no container |
| Cosign sign + attest | Provenance: a tag no Nexus é a que o job assinou, com SBOM atestado |
| Secret `nexus-registry` | k3s só puxa `nexus:8082` com credencial |

O que **não** está no gate:

- Gerar o CycloneDX em si não falha o build por CVE. Quem barra é o Trivy (FS, SBOM, imagem).
- Testes do app ainda não existem na library.
- O deploy usa tag SHA, não digest.

## Contrato do serviço

Jenkinsfile do app:

```groovy
@Library('devsecops') _

devsecopsPipeline()
```

Opcional:

```groovy
devsecopsPipeline(
  k8sDir: 'k8s',
  dockerfile: 'Dockerfile',
  context: '.'
)
```

O app precisa de:

- `Dockerfile`
- `k8s/namespace.yaml`, `k8s/deployment.yaml` (com `PLACEHOLDER_IMAGE`), `k8s/service.yaml`
- Webhook Gitea no job `dispatch`, token `dispatch`

Rede Docker: `infra_devsecops-network`. Cosign fala com o Nexus pelo hostname `nexus`.

Relatórios arquivados em `reports/`:

| Arquivo | Origem |
|---|---|
| `sbom-cyclonedx.json` | cdxgen no código |
| `gitleaks.json` | Gitleaks |
| `semgrep.json` | Semgrep |
| `trivy-fs.json` | Trivy filesystem |
| `trivy-sbom.json` | Trivy no SBOM do código |
| `sbom-image-cyclonedx.json` | Trivy `--format cyclonedx` na imagem |
| `trivy-sbom-image.json` | Trivy no SBOM da imagem |
| `trivy-image.json` | Trivy na imagem |
| `sbom-image-attestation.json` | Cosign `verify-attestation --type cyclonedx` |

## Job `dispatch`

Pipeline script from SCM → `git@gitea:admin/devsecops-pipeline.git` (Jenkinsfile deste repo: `devsecopsDispatch()`). **Não** aponte o SCM para um microserviço.

Rode **uma vez** (Build Now) para registrar o Generic Trigger. O `jenkinsEnsureJob` cria `services/<owner>-<repo>` se não existir e reescreve o `config.xml` a cada dispatch.

```groovy
jenkinsEnsureJob(folder: 'services', name: 'admin-piadas-java', displayName: 'admin/piadas-java')
```

O job gerado chama:

```groovy
@Library('devsecops') _
devsecopsPipeline()
```

O mapa fica no **Jenkinsfile do serviço** (não na library). Sem `environments`, a library usa o default do lab. Os `piadas-*` já declaram `develop` → hml e `main` → prod; no lab os dois IDs apontam para o mesmo Nexus/k3s. Numa empresa, troque só os valores:

```groovy
@Library('devsecops') _
devsecopsPipeline(
  environments: [
    develop: [
      name            : 'hml',
      REGISTRY_PUSH   : 'registry-hml:5000',
      REGISTRY_PULL   : 'registry-hml:5000',
      CRED_NEXUS      : 'nexus-hml',
      CRED_KUBECONFIG : 'k8s-hml',
      K8S_SERVER      : 'https://hml.k8s.empresa:6443'
    ],
    main: [
      name            : 'prod',
      REGISTRY_PUSH   : 'registry-prod:5000',
      REGISTRY_PULL   : 'registry-prod:5000',
      CRED_NEXUS      : 'nexus-prod',
      CRED_KUBECONFIG : 'k8s-prod',
      K8S_SERVER      : 'https://prod.k8s.empresa:6443'
    ]
  ]
)
```

| Branch | Efeito |
|---|---|
| No mapa (`develop`, `main`) | Push no registry do perfil e apply no cluster do `CRED_KUBECONFIG` |
| Fora do mapa (`feature/x`) | Scan + build. Sem push, Cosign nem Kubernetes |
| Sem `environments` | Default do topo da pipeline (Nexus + k3s), nome `prod` |

O dispatcher **não** cria nem dispara métricas.

## DORA (`jenkins-dora-metric`)

Não é job Jenkins. É o container [`jenkins-dora-metric/`](../jenkins-dora-metric/) no compose (porta **8090**). Só **lê** a API (`JENKINS_URL`, `JENKINS_USER`, `JENKINS_TOKEN`) e o `LT Ns` que o serviço já grava na description.

Página: [http://localhost:8090/](http://localhost:8090/) (compose) ou [http://localhost:30088/](http://localhost:30088/) (k3s, job `services/admin-jenkins-dora-metric`). Janelas 24h, 7d, 30d, 1 ano.

No `pipeline/infra/.env` (não vai para o Git):

```
JENKINS_USER=admin
JENKINS_TOKEN=seu-token
```

Se o job `metrics` ainda existir no Jenkins, desabilite ou apague. Token `dora` e `/userContent/dora/` não são mais usados.

| Métrica | Como o lab calcula |
|---|---|
| Lead time for changes | Média do `LT Ns` na description dos SUCCESS (commit do webhook → fim do job) |
| Deployment frequency | SUCCESS / semana na janela escolhida |
| Change failure rate | FAILURE / (SUCCESS + FAILURE) |
| Time to restore | Média FAILURE → próximo SUCCESS do mesmo job |
| Error budget consumption | Taxa de falha ÷ (1 − SLO 99%) |
| Cobertura de runbooks | `docs/runbook.md` existe no Gitea (`main`) |

## Jenkins — Global Trusted Library

Manage Jenkins → System → **Global Trusted Pipeline Libraries**:

| Campo | Valor |
|---|---|
| Name | `devsecops` |
| Default version | `main` |
| Load implicitly | não |
| Allow default version to be overridden | **não** |
| Retrieval | Modern SCM → Git |
| Project Repository | `git@gitea:admin/devsecops-pipeline.git` |
| Credentials | `gitea-ssh` |

Sem override, `@Library('devsecops@outra-branch')` não troca a pipeline.

## Credenciais Jenkins (global)

Manage Jenkins → Credentials → (global). Os IDs padrão estão no topo de `vars/devsecopsPipeline.groovy` (`CRED_*`). Senha e chave **não** vão no Git — só o ID. HML e prod: crie um kubeconfig e um `Username/password` de registry por cluster e aponte-os no `environments` do Jenkinsfile do serviço.

Em cada credencial: gere o material → escolha o **Kind** certo → cole no Jenkins com o ID da seção.

---

### `gitea-ssh`

**Kind:** *SSH Username with private key*

Clone dos apps e da library. Username sempre `git` (usuário SSH do Gitea, não o login da UI).

**Gerar o par** (no laptop):

```powershell
ssh-keygen -t ed25519 -f gitea-jenkins -N ""
```

Isso cria `gitea-jenkins` (privada) e `gitea-jenkins.pub` (pública).

**Jenkins**

1. Add Credentials → Kind **SSH Username with private key**
2. ID: `gitea-ssh`
3. Username: `git`
4. Private Key → Enter directly → cole o conteúdo de `gitea-jenkins`

**Gitea**

Usuário admin → Settings → **SSH Keys** → cole **só** a pública (`gitea-jenkins.pub`).

**Host key no container Jenkins** (senão o clone morre em *Host key verification failed*):

```bash
docker exec -u jenkins jenkins bash -lc 'mkdir -p /var/jenkins_home/.ssh && ssh-keyscan -H gitea >> /var/jenkins_home/.ssh/known_hosts'
```

Alternativa na credencial: Git Host Key Verification Configuration = **Accept first connection**.

---

### `nexus-account`

**Kind:** *Username with password*

User e senha do Nexus. A pipeline usa para `docker login`, push, Cosign e para criar o secret k8s `nexus-registry` (`.dockerconfigjson`) em cada namespace de app.

**Jenkins**

1. Add Credentials → Kind **Username with password**
2. ID: `nexus-account`
3. Username / Password: conta do Nexus com push no repo hosted **`docker`** (HTTP connector **8082**, mapeado no host como `:5000`)

Sem esse secret no namespace, o k3s não puxa `nexus:8082`.

---

### `cosign-key` · `cosign-pub` · `cosign-password`

Três IDs. O par mora em `pipeline/infra/cosign/`. Não há `cosign` no host — use a mesma imagem Chainguard da pipeline (`-it` pede a senha; `--user 0` para gravar no volume).

```powershell
cd pipeline/infra/cosign
docker run --rm -it --user 0 `
  -v "${PWD}:/work" -w /work `
  cgr.dev/chainguard/cosign:latest `
  generate-key-pair
```

Isso gera `cosign.key` e `cosign.pub` na pasta montada. O `.key` está no `.gitignore`.

| ID | Kind | Arquivo / valor |
|---|---|---|
| `cosign-key` | Secret **file** | `cosign.key` |
| `cosign-pub` | Secret **file** | `cosign.pub` |
| `cosign-password` | Secret **text** | senha pedida no `generate-key-pair` |

**Não** use a senha `devsecops`. O Jenkins mascara esse trecho e quebra o nome da rede `infra_devsecops-network` no `docker run` do Cosign.

---

### `k3s-kubeconfig`

**Kind:** Secret **file**

Kubeconfig do ServiceAccount `cicd/jenkins-deploy`. **Não** é o `k3s.yml` admin.

O manifesto já está neste repo: [`infra/k3s/jenkins-deploy.yaml`](infra/k3s/jenkins-deploy.yaml).

1. Copie o kubeconfig **admin** (só no laptop; gitignore):

```powershell
docker cp k3s-server:/etc/rancher/k3s/k3s.yaml pipeline/infra/k3s.yml
$env:KUBECONFIG = "d:\DevSecOps-curso\pipeline\infra\k3s.yml"
```

2. Aplique o RBAC **uma vez**:

```powershell
kubectl apply -f pipeline/infra/k3s/jenkins-deploy.yaml
```

3. Leia o token do secret:

```powershell
kubectl -n cicd get secret jenkins-deploy-token -o jsonpath="{.data.token}"
```

No PowerShell, decodifique o Base64 antes de colar:

```powershell
$b64 = kubectl -n cicd get secret jenkins-deploy-token -o jsonpath="{.data.token}"
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
```

4. Monte um kubeconfig de **cliente** (`pipeline/infra/cicd.yaml`). `kind: Config` — **não** dê `kubectl apply` nesse arquivo.

```yaml
apiVersion: v1
kind: Config
clusters:
- cluster:
    insecure-skip-tls-verify: true
    server: https://127.0.0.1:6443
  name: k3s
contexts:
- context: { cluster: k3s, user: jenkins-deploy }
  name: jenkins-deploy
current-context: jenkins-deploy
users:
- name: jenkins-deploy
  user: { token: <cole o token decodificado> }
```

5. Jenkins → Add Credentials → Kind **Secret file** → ID `k3s-kubeconfig` → escolha `cicd.yaml`.

6. O job já força `serverUrl: https://host.docker.internal:6443`. Recreate do k3s invalida o token — gere de novo o `cicd.yaml`.

---

### `jenkins-api`

**Kind:** *Username with password*

Usuário Jenkins + **API Token** (usuário → Configure → API Token). Precisa poder criar jobs.

O `jenkinsEnsureJob` cria a pasta `services` e o `WorkflowJob` `services/<owner>-<repo>` via HTTP API + crumb.

---

### SMTP (`emailext`)

Não é credencial da pipeline. Configure **Extended E-mail Notification** (host, porta, SSL/TLS) e **Jenkins Location → System Admin e-mail** no mesmo domínio do `From` (`contato@henrks.com`).

## Como manter / plano de continuidade

### Mudar a pipeline

Edite `vars/` → commit em `main` → `git push origin` (Gitea; a Trusted Library lê daqui) e `git push github`. Sem override de versão: o próximo job de serviço já puxa `main`.

Se library e app mudarem no mesmo ciclo, publique a library **antes** do push do app.

### Novo microserviço

1. Repo no Gitea com Jenkinsfile de três linhas, `Dockerfile` e `k8s/` (namespace, deployment com `PLACEHOLDER_IMAGE`, service). Família de teste: `piadas-<linguagem>` (`piadas-java`, `piadas-python`, `piadas-node`, `piadas-go`, `piadas-dotnet`).
2. Webhook Gitea → job `dispatch`, token `dispatch`.
3. Primeiro push cria `services/<owner>-<repo>`. O SCM do job é reescrito a cada dispatch.

### Versões de ferramenta

Pins em `environment {}` em `vars/devsecopsPipeline.groovy`:

| Variável | Imagem |
|---|---|
| `GITLEAKS_IMAGE` | `ghcr.io/gitleaks/gitleaks:v8.28.0` |
| `SEMGREP_IMAGE` | `semgrep/semgrep:1.128.0` |
| `TRIVY_IMAGE` | `aquasec/trivy:0.74.0` |
| `CYCLONEDX_IMAGE` | `ghcr.io/cdxgen/cdxgen:v12` |
| `MAVEN_IMAGE` | `maven:3.9.9-eclipse-temurin-21` |
| `COSIGN_IMAGE` | Chainguard `cosign:latest` **com digest** |

`ghcr.io/cyclonedx/cdxgen` **não existe** — use `ghcr.io/cdxgen/cdxgen:v12`. Subir versão = editar o pin, commitar, publicar.

### Rotação de credenciais

| Material | Quando regerar |
|---|---|
| Par SSH `gitea-ssh` | Chave vazou ou Gitea perdeu a pública |
| Token `jenkins-api` | Usuário Jenkins recriado ou token revogado |
| Token k3s (`cicd.yaml`) | Recreate do k3s (invalida o SA) |
| Par Cosign em `pipeline/infra/cosign/` | Chave vazou; `.key` é gitignorado |

IDs no Jenkins **não mudam**. Só o conteúdo da credencial.

### Volumes e lab

Compose em [`infra/`](infra/docker-compose.yml). Caches: `maven-cache` (`/root/.m2` no container Maven), `trivy-cache` montado em `/var/tmp/trivy` (um diretório por job/stage — o lock único do Trivy derruba o `CycloneDX image` se dois jobs compartilham o mesmo cache). Estado do Jenkins: volume do container.

Subir o lab:

```powershell
cd pipeline/infra
copy .env.example .env   # preencha JENKINS_TOKEN
docker compose up -d --build
```

DORA no lab: [http://localhost:8090/](http://localhost:8090/). Sem token o container sobe, mas a página responde 503.

### Recuperação

- k3s Node Ready: `--flannel-iface=eth0` e `--disable-network-policy` (não `--disable=network-policy`).
- Clone no Jenkins: host key com `ssh-keyscan gitea` no container. URL interna `git@gitea:…`.
- Clone no laptop: HTTP `http://localhost:8082/admin/<repo>.git`. SSH do Windows usa a porta `2222` — **não** ponha `Host localhost` Port 2222 no ssh config (quebra o resto do localhost).
- Cosign: senha **nunca** `devsecops` (máscara do Jenkins quebra `--network infra_devsecops-network`).

### Fora de escopo da library

- Testes unitários / de integração do app
- Deploy por digest (hoje é tag SHA)
- `@Library('devsecops@outra-branch')` (override desligado de propósito)
