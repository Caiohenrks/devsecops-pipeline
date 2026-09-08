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

O job genérico (`docker-build-and-push`) deve usar o Jenkinsfile **deste** repo (ou o da raiz do curso). Assim um push no app não troca as stages.

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
