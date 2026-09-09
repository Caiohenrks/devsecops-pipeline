# Implementar a shared library num Jenkins

Jenkins já está no ar. Este guia só liga a library `devsecops`: plugins, credenciais, Trusted Library, job `dispatch` e webhook no Git.

Ordem: **plugins → System → credenciais → library → `dispatch` → webhook**. Sem um desses, o push não cria `services/<owner>-<repo>`.

Detalhe de cada credencial: [README](../README.md#credenciais-jenkins-global).

O node que executa os jobs precisa de `docker` e `kubectl` no PATH (build/push da imagem e apply no cluster) e de rede até o Git, o registry e a API do Kubernetes. A URL do Jenkins (`JENKINS_URL`) tem de responder **no próprio node** — o `dispatch` cria jobs via API + crumb.

---

## 1. Plugins

Manage Jenkins → Plugins → Available. Reinicie depois de instalar.

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

Stage View e Timestamper são opcionais.

---

## 2. System

Manage Jenkins → System / Security:

| Item | Valor |
|---|---|
| Jenkins URL | URL pública do controller (links do e-mail e da API) |
| System Admin e-mail | Mesmo domínio do `MAIL_FROM` da pipeline |
| CSRF / crumb issuer | Ligado — o `jenkinsEnsureJob` envia crumb |
| Git Host Key Verification | **Accept first connection** (ou cadastre o host key do Git no `known_hosts` do user que roda o job) |

Sem host key o clone morre em *Host key verification failed*.

---

## 3. Credenciais (global)

Manage Jenkins → Credentials → (global). Use **estes IDs** (são os defaults da library). Kind e como gerar: [README](../README.md#credenciais-jenkins-global).

| ID | Kind | Para |
|---|---|---|
| `gitea-ssh` | SSH Username with private key | Clone da library e dos apps. Username SSH: `git` |
| `nexus-account` | Username/password | Login no registry, Cosign, secret k8s `nexus-registry` |
| `cosign-key` | Secret file | chave privada Cosign |
| `cosign-pub` | Secret file | chave pública Cosign |
| `cosign-password` | Secret text | senha do par. **Não** use `devsecops` (o Jenkins mascara e quebra o `docker run`) |
| `k3s-kubeconfig` | Secret file | kubeconfig do SA de deploy |
| `jenkins-api` | Username/password | usuário Jenkins + **API Token** |

`jenkins-api`: Overall/Read, Job/Create, Job/Configure, Job/Read, Job/Build. Token em usuário → Configure → API Token (a senha da UI não substitui o token).

Para HML/prod, crie um par registry + kubeconfig por cluster e aponte os IDs no `environments` do Jenkinsfile do serviço — não precisa mudar a library.

---

## 4. Defaults da pipeline

Os valores no topo de `vars/devsecopsPipeline.groovy` são do lab. Num Jenkins da empresa, troque ali **ou** no `devsecopsPipeline(environments: [...])` de cada app:

| Variável | Papel |
|---|---|
| `GIT_INTERNAL_HOST` | Hostname do Git que o node resolve no clone SSH |
| `REGISTRY_PUSH` | Registry do `docker push` (visto pelo node) |
| `REGISTRY_PULL` | Registry que o cluster usa no `image:` |
| `REGISTRY_REPO` | Path no registry (lab: `docker`) |
| `K8S_SERVER` | API do cluster no `withKubeConfig` (vazio = server do kubeconfig) |
| `DOCKER_NETWORK` | Rede dos `docker run` do Cosign até o registry |
| `MAIL_FROM` | Remetente do `emailext` |
| `CRED_*` | Só se os IDs das credenciais forem outros |

Registry HTTP exige `insecure-registries` no daemon que o node usa.

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
| Project Repository | URL SSH deste repo (lab: `git@gitea:admin/devsecops-pipeline.git`) |
| Credentials | `gitea-ssh` |

O `@Library('devsecops') _` dos Jenkinsfiles **não** resolve sem este nome. Sem override, `@Library('devsecops@outra-branch')` não troca a pipeline.

Publique `main` neste repo **antes** do primeiro job de serviço.

---

## 6. Job `dispatch`

New Item:

1. Name: `dispatch`.
2. Type: **Pipeline** → OK.
3. Definition: **Pipeline script from SCM**.
4. SCM: Git → URL deste repo → credencial `gitea-ssh`.
5. Branch: `*/main`.
6. Script Path: `Jenkinsfile` (`devsecopsDispatch()`).
7. Save.

**Build Now uma vez.** Sem payload o stage só registra o Generic Trigger (`trigger registered`). Sem esse build o webhook não tem endpoint.

URL do invoke:

`https://<seu-jenkins>/generic-webhook-trigger/invoke?token=dispatch`

O token **`dispatch`** está em `vars/devsecopsDispatch.groovy`.

A cada push o job cria/atualiza `services/<owner>-<repo>` (`jenkins-api`) e dispara o Pipeline from SCM do **app**. Ignora `admin/devsecops-pipeline` e `admin/curso`.

Não aponte o SCM do `dispatch` para um microserviço.

---

## 7. Webhook no repo do app

No Git (Gitea/GitHub/GitLab) de **cada** microserviço:

| Campo | Valor |
|---|---|
| URL | `https://<seu-jenkins>/generic-webhook-trigger/invoke?token=dispatch` |
| Method | POST |
| Content type | `application/json` |
| Secret | vazio (o token vai na query) |
| Evento | **Push** |

Delivery de teste: HTTP 200. O próximo push cria `services/<owner>-<repo>` e dispara o job.

---

## 8. Contrato do app

No repositório do serviço:

- `Jenkinsfile` com `@Library('devsecops') _` e `devsecopsPipeline(...)`
- `Dockerfile`
- `k8s/namespace.yaml`, `k8s/deployment.yaml` (`PLACEHOLDER_IMAGE`), `k8s/service.yaml`

Mapa `environments` (branch → registry/cluster): [README](../README.md#job-dispatch).

---

## 9. Conferência

1. `dispatch` #1 = SUCCESS, description `trigger registered`.
2. Push num app → `dispatch` cria `services/<owner>-<repo>` e dispara o job.
3. Clone sem *Host key verification failed*.
4. Security gate corre (falha por CVE/secret é esperada; falha por plugin ausente não).
5. Imagem no registry; Cosign `verify` + `verify-attestation`.
6. Rollout no cluster; secret `nexus-registry` no namespace.
7. Description do SUCCESS contém `LT <n>s`.

---

## 10. Se quebrar

| Sintoma | Onde olhar |
|---|---|
| Webhook 404 / não dispara | `dispatch` sem Build Now; URL sem `?token=dispatch`; plugin Generic Webhook Trigger |
| `dispatch` SUCCESS sem job do app | `jenkins-api` sem permissão ou crumb; `JENKINS_URL` no node não aponta para este controller |
| *Host key verification failed* | Accept first connection ou `known_hosts` do Git |
| *Could not find library `devsecops`* | Name da Trusted Library ≠ `devsecops` ou SCM/credencial |
| Cosign / `docker run` some argumento | Senha Cosign = `devsecops` (máscara) |
| Push HTTP recusado | `insecure-registries` no daemon do node |
| ImagePullBackOff | Secret `nexus-registry` / `CRED_NEXUS`; `REGISTRY_PULL` que o cluster resolve |
| Token k8s 401 | kubeconfig/SA regenerado |

Mais: [README — Recuperação](../README.md#recuperação).
