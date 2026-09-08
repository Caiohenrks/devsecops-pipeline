# Pipeline DevSecOps (shared library)

Equivalente ao reusable workflow do GitHub Actions.

O código real (scan, build, Trivy, Cosign, k3s, e-mail) fica **neste** repositório. O desenvolvedor do serviço **não** edita isso.

No Gitea: `admin/devsecops-pipeline` (só o time de plataforma escreve).

## Publicar no Gitea

```powershell
cd pipeline
git init
git add .
git commit -m "shared pipeline"
git branch -M main
git remote add origin ssh://git@localhost:2222/admin/devsecops-pipeline.git
git push -u origin main
```

Crie o repo vazio `admin/devsecops-pipeline` no Gitea antes do push.

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

O job **`dispatch`** usa o Jenkinsfile **deste** repo (`devsecopsDispatch()`), **não** o do `crud-user`/`piadas`. SCM: `git@gitea:admin/devsecops-pipeline.git`. Token do webhook (Gitea): `build-and-push`.

Credencial **Username with password**, ID `jenkins-api`: usuário Jenkins + API token (usuário → Configure → API Token). O step `jenkinsEnsureJob` cria `services/<owner>-<repo>` via REST se não existir.

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
