# Pipeline DevSecOps (shared library)

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

Manage Jenkins → Credentials → (global). Os IDs **têm que ser exatamente estes**.

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

---

## Job `dispatch`

Pipeline script from SCM → `git@gitea:admin/devsecops-pipeline.git` (Jenkinsfile deste repo: `devsecopsDispatch()`). **Não** aponte o SCM para `crud-user` / `piadas` / `agente`.

Rode **uma vez** (Build Now) para registrar o Generic Trigger. O `jenkinsEnsureJob` cria `services/<owner>-<repo>` se não existir.

```groovy
jenkinsEnsureJob(folder: 'services', name: 'admin-piadas', displayName: 'admin/piadas')
```

O job gerado chama:

```groovy
@Library('devsecops') _
devsecopsPipeline()
```

Métricas (frequência, falha, lead time) leem o job do serviço, não o dispatcher. `admin/devsecops-pipeline` e `admin/curso` são ignorados.

## O que o serviço pode passar

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

Falha de stage é **FAILURE**. O `try/catch` só registra o motivo no console (e no e-mail) antes do `error()`. Os scans em paralelo terminam todos; o stage **Security gate** junta as mensagens e aborta.
