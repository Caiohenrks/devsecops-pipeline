# Instalar a shared library num Jenkins

Ordem: **agent Docker → plugins → credenciais → Trusted Library → job `dispatch` → webhook**. Sem um desses, o push no Gitea não cria `services/<owner>-<repo>`.

Detalhe de cada credencial: [README — Credenciais](../README.md#credenciais-jenkins-global).

## O que este Jenkins precisa

A library **não** roda as ferramentas no agent. Ela faz `docker run` (scans, Cosign) e `docker build` / `push`. O controller (ou o agent que executar os jobs) tem de:

| Requisito | Por quê |
|---|---|
| `docker` CLI no PATH do user Jenkins | `docker build`, `run`, `push`, `login` |
| Socket `/var/run/docker.sock` montado | O daemon é o do host |
| Permissão de escrever no socket (lab: `group_add: ["0"]`) | Senão `permission denied` |
| Container **chamado `jenkins`** | A pipeline usa `--volumes-from jenkins` para o workspace aparecer nos scanners |
| `kubectl` no PATH | Stage Kubernetes (`withKubeConfig`) |
| Rede até Gitea, Nexus e a API do cluster | Clone SSH, Cosign/`docker push`, apply |
| `JENKINS_URL` alcançável **de dentro** do container | `jenkinsEnsureJob` chama a API + crumb (no lab: `http://127.0.0.1:8080`) |

Jenkins instalado como serviço no host (sem container `jenkins`) **não** serve para esta library sem mudar o `docker run --volumes-from`. Use a imagem do lab ou um controller em Docker com esse nome.

Imagem do lab ([`infra/jenkins/Dockerfile`](../infra/jenkins/Dockerfile)): `jenkins/jenkins:lts` + `docker-ce-cli` + `kubectl` 1.34.

---

## 1. Subir o lab (compose)

```powershell
cd pipeline/infra
copy .env.example .env
# preencha JENKINS_TOKEN só se for usar o container DORA na :8090
docker compose up -d --build
```

| Serviço | Host |
|---|---|
| Jenkins | [http://localhost:8080](http://localhost:8080) |
| Nexus | [http://localhost:8081](http://localhost:8081) · registry Docker `127.0.0.1:5000` → `8082` |
| Gitea | [http://localhost:8082](http://localhost:8082) · SSH `localhost:2222` |
| k3s API | `https://127.0.0.1:6443` |
| DORA | [http://localhost:8090](http://localhost:8090) (compose) / [http://localhost:30088](http://localhost:30088) (k3s) |

Rede Docker: `infra_devsecops-network` (`DOCKER_NETWORK` no topo de `vars/devsecopsPipeline.groovy`). Cosign usa `--network` nessa rede para falar `nexus:8082`.

### 1.1 Desbloquear o Jenkins

```powershell
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Abra `:8080`, cole a senha, crie o admin. Instale os **plugins sugeridos** e, em seguida, os da [seção 2](#2-plugins) (Available → Restart quando pedir).

### 1.2 Docker Desktop — registry inseguro

O push do lab vai para `127.0.0.1:5000` (HTTP). Em Docker Engine:

```json
{
  "insecure-registries": ["127.0.0.1:5000"]
}
```

Aplique e reinicie o Docker. Sem isso o `docker push` falha com *http: server gave HTTP response to HTTPS client*.

### 1.3 Nexus — repo Docker

UI `:8081` (senha inicial: `docker exec nexus cat /nexus-data/admin.password`).

1. Enable **Docker Bearer Token Realm** (Security → Realms).
2. Create repository → **docker (hosted)**.
3. Name: `docker` (é o `REGISTRY_REPO` da pipeline).
4. HTTP connector: **8082** (já publicado no host como `:5000`).
5. Allow anonymous docker pull: opcional no lab; o job sempre faz login com `nexus-account`.

Crie um usuário com permissão de push nesse repo (ou use `admin`) e cadastre-o no Jenkins como `nexus-account`.

### 1.4 Gitea

1. Primeiro usuário = admin. `ROOT_URL` já é `http://localhost:8082/`.
2. Crie a org/user `admin` e o repo `devsecops-pipeline` (este código). Remote HTTP sem token no URL:

```powershell
cd pipeline
git remote add origin http://localhost:8082/admin/devsecops-pipeline.git
git push -u origin main
```

3. Settings → SSH Keys: pública do par `gitea-ssh` ([README](../README.md#gitea-ssh)).
4. `ALLOWED_HOST_LIST` no compose já inclui `jenkins` (webhook interno).

### 1.5 k3s — pull do Nexus e SA de deploy

O cluster já sobe com [`infra/k3s/registries.yaml`](../infra/k3s/registries.yaml) (`nexus:8082` → HTTP). NodePorts `30080`–`30088` estão no compose.

RBAC + kubeconfig: [README — `k3s-kubeconfig`](../README.md#k3s-kubeconfig). Flags do server: `--flannel-iface=eth0` e `--disable-network-policy`.

---

## 2. Plugins

Manage Jenkins → Plugins → Available. Reinicie o Jenkins depois.

| Plugin | ID | Uso |
|---|---|---|
| Pipeline | `workflow-aggregator` | Jenkinsfile / `pipeline { }` |
| Git | `git` | SCM da library e dos apps |
| Credentials Binding | `credentials-binding` | `withCredentials` |
| SSH Credentials | `ssh-credentials` | Kind *SSH Username with private key* |
| Folders | `cloudbees-folder` | Pasta `services/` |
| Generic Webhook Trigger | `generic-webhook-trigger` | `GenericTrigger` no `dispatch` |
| Kubernetes CLI | `kubernetes-cli` | `withKubeConfig` |
| Email Extension | `email-ext` | `emailext` no `post` |
| Workspace Cleanup | `ws-cleanup` | `cleanWs()` |

Pipeline: Stage View e Timestamper são opcionais.

**Git Host Key:** Manage Jenkins → Security → *Git Host Key Verification Configuration* → **Accept first connection**, **ou** `ssh-keyscan` no container (seção `gitea-ssh` do README). Sem um dos dois o clone morre em *Host key verification failed*.

**Jenkins Location:** Manage Jenkins → System → Jenkins URL = `http://localhost:8080/` (links do e-mail). System Admin e-mail no mesmo domínio do `MAIL_FROM` (`contato@henrks.com` no lab).

**CSRF:** deixe o crumb issuer ligado. O `jenkinsEnsureJob` envia crumb em todo `createItem` / `config.xml`.

---

## 3. Jenkins que já existe (não é o compose)

1. Rode o controller **como container** com nome `jenkins`, socket Docker, `docker` CLI e `kubectl` (reaproveite o Dockerfile do lab).
2. Coloque Gitea/Nexus/cluster na **mesma rede** Docker, ou ajuste no Jenkinsfile/`environment {}`:
   - `GIT_INTERNAL_HOST` — hostname SSH do Git (lab: `gitea`)
   - `REGISTRY_PUSH` / `REGISTRY_PULL` / `REGISTRY_REPO`
   - `K8S_SERVER`
   - `DOCKER_NETWORK` — nome real da bridge (`docker network ls`)
3. `JENKINS_URL` dentro do container tem de responder (API do `jenkinsEnsureJob`). Se o Jenkins não escuta em `127.0.0.1:8080` no próprio container, passe `jenkinsUrl:` no dispatch ou defina a URL em Manage Jenkins → System.
4. Credenciais com **os mesmos IDs** (ou troque os `CRED_*` / `jenkins-api` / `gitea-ssh`).
5. Siga [plugins](#2-plugins) → credenciais → library → `dispatch` → webhook.

Não mude IDs no Git “para testar”: a library e o `jenkinsEnsureJob` usam os defaults (`gitea-ssh`, `nexus-account`, `jenkins-api`, …).

---

## 4. Credenciais (checklist)

Manage Jenkins → Credentials → (global). Kind e material: [README](../README.md#credenciais-jenkins-global).

| ID | Kind | Para |
|---|---|---|
| `gitea-ssh` | SSH Username with private key | Clone library + apps. Username `git` |
| `nexus-account` | Username/password | `docker login`, Cosign, secret `nexus-registry` |
| `cosign-key` | Secret file | `cosign.key` |
| `cosign-pub` | Secret file | `cosign.pub` |
| `cosign-password` | Secret text | senha do par. **Nunca** `devsecops` |
| `k3s-kubeconfig` | Secret file | `cicd.yaml` do SA `cicd/jenkins-deploy` |
| `jenkins-api` | Username/password | user Jenkins + **API Token** |

`jenkins-api` precisa criar pasta/job e disparar build: Overall/Read, Job/Create, Job/Configure, Job/Read, Job/Build. Admin do lab serve.

Gere o token: usuário → Configure → API Token. A senha de login **não** substitui o token se a API estiver restrita.

---

## 5. Global Trusted Library

Manage Jenkins → System → **Global Trusted Pipeline Libraries** → Add:

| Campo | Valor |
|---|---|
| Name | `devsecops` |
| Default version | `main` |
| Load implicitly | não |
| Allow default version to be overridden | **não** |
| Retrieval | Modern SCM → Git |
| Project Repository | `git@gitea:admin/devsecops-pipeline.git` |
| Credentials | `gitea-ssh` |

O `@Library('devsecops') _` nos Jenkinsfiles **não** funciona sem este nome. Sem override, `@Library('devsecops@outra-branch')` não troca a pipeline.

---

## 6. Job `dispatch`

New Item:

1. Name: `dispatch` (exato — o webhook e o hábito do lab usam esse job).
2. Type: **Pipeline** → OK.
3. Pipeline → Definition: **Pipeline script from SCM**.
4. SCM: Git.
5. Repository URL: `git@gitea:admin/devsecops-pipeline.git` — **não** aponte para um `piadas-*`.
6. Credentials: `gitea-ssh`.
7. Branch specifier: `*/main`.
8. Script Path: `Jenkinsfile` (chama `devsecopsDispatch()`).
9. Save.

**Build Now uma vez.** Sem payload o stage só registra o Generic Trigger (`trigger registered`). Sem esse build, o Gitea toma 404/401 no webhook.

Console do plugin (ou a descrição do build): o invoke fica em

`http://<jenkins>/generic-webhook-trigger/invoke?token=dispatch`

De dentro da rede Docker (webhook do Gitea):

`http://jenkins:8080/generic-webhook-trigger/invoke?token=dispatch`

O token **`dispatch`** está fixo em `vars/devsecopsDispatch.groovy`.

A cada push o job cria/atualiza `services/<owner>-<repo>` via API (`jenkins-api`) e dispara o Pipeline from SCM do **app**. Ignora `admin/devsecops-pipeline` e `admin/curso`.

---

## 7. Webhook no repo do microserviço

Em cada repo de app (Gitea → Settings → Webhooks → Add Webhook → Gitea):

| Campo | Valor |
|---|---|
| Target URL | `http://jenkins:8080/generic-webhook-trigger/invoke?token=dispatch` |
| HTTP Method | POST |
| Content type | `application/json` |
| Secret | vazio (o token vai na query) |
| Trigger | **Push** |

Test delivery: 200. Aí um push em `main`/`develop` deve aparecer no job `dispatch` e nascer `services/admin-piadas-java` (etc.).

No laptop o clone HTTP é `http://localhost:8082/admin/<repo>.git`. Não embuta token no remote.

---

## 8. Contrato do app (para o job funcionar)

No repo do serviço:

- `Jenkinsfile` com `@Library('devsecops') _` e `devsecopsPipeline(...)`
- `Dockerfile`
- `k8s/namespace.yaml`, `k8s/deployment.yaml` (`PLACEHOLDER_IMAGE`), `k8s/service.yaml`

Mapa `environments` (opcional): [README](../README.md#job-dispatch).

---

## 9. Conferência

1. `dispatch` #1 = SUCCESS, description `trigger registered`.
2. Push num `piadas-*` → `dispatch` cria `services/admin-piadas-…` e dispara o job.
3. Clone não pede *Host key verification failed*.
4. Security gate passa (ou falha por CVE/secret — não por plugin ausente).
5. `docker push` em `127.0.0.1:5000/docker/...`.
6. Cosign `verify` + `verify-attestation` no Nexus.
7. Pod Ready no k3s; NodePort do `service.yaml`.
8. Description do SUCCESS contém `LT <n>s` (DORA lê isso).

---

## 10. Se quebrar

| Sintoma | Onde olhar |
|---|---|
| Webhook 404 / trigger não dispara | Job `dispatch` nunca teve Build Now; URL sem `?token=dispatch`; plugin Generic Webhook Trigger |
| `dispatch` SUCCESS mas sem job do app | `jenkins-api` sem permissão ou crumb; `JENKINS_URL` errada dentro do container |
| *Host key verification failed* | `ssh-keyscan gitea` ou Accept first connection |
| *Could not find library `devsecops`* | Trusted Library name ≠ `devsecops` ou SCM inacessível |
| `--volumes-from`: no container `jenkins` | `container_name` não é `jenkins` |
| Cosign / `docker run` some o `--network` | Senha Cosign = `devsecops` (máscara do Jenkins) |
| Push HTTPS vs HTTP | `insecure-registries` sem `127.0.0.1:5000` |
| k3s ImagePullBackOff | Secret `nexus-registry` / `nexus-account`; `registries.yaml` |
| Token k3s 401 | Recreate do k3s — gere de novo o `cicd.yaml` |

Mais: [README — Recuperação](../README.md#recuperação).
