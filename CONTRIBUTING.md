# Contribution and deployment flow

The source of truth is `LallexTech/2flygare-calendar`. `main` is production and `staging` is the shared pre-production branch. Direct pushes, force pushes, and deletion are blocked on both protected branches. Create and push feature branches from `staging`, then use pull requests for both promotions.

## Start a feature locally

In PowerShell, from the project folder:

```powershell
cd D:\Projekt\2flygare
git status
git switch staging
git pull --ff-only origin staging
git switch -c feature/short-description
```

Make and save changes on that feature branch. Then commit and push it:

```powershell
git add <files>
git commit -m "feat: short description"
git push -u origin feature/short-description
```

Open a GitHub pull request with `feature/short-description` as the source and `staging` as the base. GitHub requires the `Validate source branch` check. Vercel creates an individual Preview deployment for feature-branch pushes and pull requests. Generated Preview URLs other than the stable `-pre` alias may require Vercel authentication.

## Deploy to Preview

After the PR check succeeds, merge the feature PR into `staging`. Vercel automatically builds the updated branch using Preview environment variables and assigns the latest deployment to `https://2flygarecalender-pre.vercel.app`. Review the application there. Preview uses the Neon `2flygare-preview` project's `main` database branch; it is separate from Production.

## Promote to Production

When the release is approved, open a second pull request from `staging` into `main`. The branch policy rejects any other source for `main`. Review its changes and checks, then merge it only after explicit release approval. This triggers Vercel Production with Production-scoped variables and the separate Neon `production` database branch. The production address is `https://2flygarecalender.vercel.app`.

Vercel deploys Git pushes and pull requests automatically; routine releases do not require the Vercel CLI. A merge into `staging` updates Preview. A merge into `main` updates Production. Do not merge a `staging`-to-`main` PR before release approval because it deploys to the public Production domain.

The branch policy workflow rejects fork pull requests and enforces the `feature -> staging -> main` source path. Branch protection requires PRs and the check on both `staging` and `main`, applies to administrators, and blocks force pushes and deletion. The repository currently has one writer, `LallexTech`, so no separate reviewer approval is required for the solo maintainer workflow.

## Repository access

The repository is public, but `LallexTech` is the only account with write access. Issues, Discussions, Projects, and the wiki are disabled. GitHub allows forks of public repositories on personal accounts, but the branch policy rejects pull requests whose source repository is a fork. Only a branch pushed to this repository can pass the required check, and only the owner can merge it.
