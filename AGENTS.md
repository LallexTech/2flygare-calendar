# Project context and working rules

Read this file at the start of work in this repository. It records the architecture, production boundaries, and release process. For the user-facing setup and configuration details, also read [README.md](README.md); for the exact Git commands, read [CONTRIBUTING.md](CONTRIBUTING.md).

## Project identity

- Product: 2Flygare Calendar, a Swedish construction-company resource and activity planner.
- Active source repository: public GitHub repo `LallexTech/2flygare-calendar`.
- The earlier repo was renamed to the private archive `2flygare-calendar-legacy-20261103`; do not use it as a source or reconnect it to deployments.
- Vercel project: `2flygare`, connected to the active GitHub repo.
- User-facing production URL: `https://2flygarecalender.vercel.app`.
- Shared pre-production URL: `https://2flygarecalender-pre.vercel.app`.

## Technology and architecture

- Backend: Java 21 and Spring Boot 4, built with Maven (`pom.xml`). The application runs as a Vercel beta container.
- Web rendering: Spring MVC and Thymeleaf server-rendered templates under `src/main/resources/templates`.
- Browser code: JavaScript and CSS under `frontend/` and `src/main/resources/static/`. The planner uses `vis-timeline`, `vis-data`, and `moment-timezone`, bundled by esbuild (`npm run build`). This is not a React/TypeScript frontend.
- Database: Neon PostgreSQL, accessed with Spring JDBC. Flyway migrations live in `src/main/resources/db/migration`; shared JDBC sessions persist sign-in sessions across container instances.
- Container: `Dockerfile` builds browser assets with Node 24, the application with Maven/Temurin 21, and runs the Temurin 21 JRE as an unprivileged user. Vercel deploys in `fra1` as set in `vercel.json`.
- Main application areas: `SecurityConfig`, `GoogleLoginConfig`, `GoogleIdentity`, `Accounts`, and `BootstrapService` handle identity/security; `PlanningController` and `PlanningService` handle planning APIs and rules; `AdminController` and `Administration` handle administrative workflows. Templates and planner JavaScript implement the UI.

## Authentication and access model

- Deployed sign-in uses Spring Security's Google OpenID Connect authorization-code flow with PKCE. Google credentials remain server-side Vercel variables. Do not add client secrets, tokens, real admin emails, or database URLs to source control or logs.
- Production email-link login is disabled. Legacy one-time-token code exists only for explicit isolated tests and must remain disabled in deployed configuration.
- Google-verified identities are normalized and linked to local accounts. Personal Gmail dots and `googlemail.com` are canonicalized safely; a Google subject cannot silently take over another identity.
- A configured and verified initial administrator may be granted `ADMIN` and `APPROVED` by the idempotent bootstrap ledger. Other new identities remain `MEMBER/PENDING` until an administrator approves them. Never invent administrator email addresses; configure only real addresses explicitly supplied by the owner.
- Authorization is checked against the database on protected requests. Approval is required for planning access; administration requires `ADMIN`. The Developer designation is informational and is not a role or security bypass.

## Runtime environments and data separation

- Vercel Production tracks Git branch `main`; Vercel Preview includes feature branches and `staging`.
- `2flygarecalender-pre.vercel.app` is a branch domain assigned to `staging`; each feature branch/PR gets its own generated Preview URL. The stable alias follows `staging` automatically after its Git deployment.
- Preview/Development runtime variables point to the Vercel-managed Neon project `2flygare-preview`, default branch `main` (the pre-production database). Production variables point to the separate Neon branch `production`, created as a point-in-time copy on 2026-11-03. Changes do not automatically copy between these database branches.
- Production and Preview have separate `DATABASE_JDBC_URL`, database credentials, Google OAuth configuration, and app base URL. Never copy Production credentials into Preview or vice versa. The retained `AUTH_*` values belong to an older Neon Auth resource; Spring Security Google sign-in does not use Neon Auth.
- A Vercel domain alias changes only the hostname. It does not change the deployment's target or its environment variables.
- Random generated Preview URLs are behind Vercel Deployment Protection. The stable `-pre` alias has an exception so users reach application Google sign-in directly. Application sign-in and Vercel deployment protection are separate controls.
- Local `.env.local` is not committed. Spring Boot does not load it automatically; use a deliberate local environment-loading method. `.env.example` contains variable names only. Never print environment-variable values. Prefer commands that list names and scopes only; avoid `vercel env ls --json` because JSON includes readable values.

## GitHub and release process

The intended flow is:

```text
feature branch -> PR to staging -> merge -> Vercel Preview / pre URL
staging -> PR to main -> explicit release approval -> merge -> Vercel Production
```

- Start every feature branch from the latest `staging`, not from `main`.
- Push the feature branch and open a PR targeting `staging`. The required GitHub Actions check is named `Validate source branch`.
- Only merge the feature PR after its required check succeeds and the change is ready for shared Preview.
- Verify the result at `https://2flygarecalender-pre.vercel.app`. Preview uses the Preview-scoped environment and pre-production Neon branch.
- Promote by opening a PR from `staging` to `main`. Once `.github/workflows/promote-staging.yml` has reached `main`, start it from GitHub **Actions → Promote staging to production → Run workflow** while on `main`; it creates or reuses the promotion PR, but never merges it. Review and merge that PR only after the user explicitly approves the release. Merging into `main` automatically deploys Production.
- The promotion Action uses the short-lived `GITHUB_TOKEN` with `contents: read` and `pull-requests: write`; do not replace it with a personal token. The repository setting allowing Actions to create pull requests is enabled; GitHub groups PR creation and PR approval under this setting, but the promotion workflow itself only creates/reuses a PR and never approves or merges it. If GitHub requires approval for workflows triggered by the bot-created PR, have a repository writer approve the run so `Validate source branch` can execute.
- GitHub only exposes the manual **Run workflow** button after the workflow exists on the default branch. For the initial installation, create the first `staging`-to-`main` promotion PR manually after Preview verification and explicit release approval. That one-time PR also promotes any other pending `staging` changes; review its full diff before merge.
- Branch protection on `staging` and `main` requires a PR and `Validate source branch`, applies to admins, and disallows force pushes and branch deletion. Required approval count is zero for the solo-owner workflow.
- `LallexTech` is currently the only account with write permission. Issues, Discussions, Projects, and the wiki are disabled. Public personal repositories can still be forked; fork PRs are rejected by the branch-policy workflow. A public fork cannot write to or merge into the source repository.
- GitHub Actions workflow source is `.github/workflows/branch-policy.yml`. It rejects fork PRs, disallows `main` as a source into `staging`, and requires `staging` as the source into `main`.
- Routine Vercel deployments are automatic from Git. Do not manually deploy to Production with the Vercel CLI unless the user explicitly asks. A manual CLI deploy is a Preview unless Production is explicitly selected.

### Current release status (2026-11-03)

- The Vercel Git connection to the new public repo is active; production branch is `main`, and the pre domain follows `staging`.
- PR #2 (the initial `staging` → `main` promotion) was merged. Its production deployment is complete.
- PR #5 (`feature/planner-default-0600-1800` → `staging`) was merged. The 06:00–18:00 planner default is now on `staging` and is available through the shared Preview deployment.
- PR #2 is closed; there is no current promotion PR. Check whether `staging` has new commits ahead of `main` before opening or creating another promotion PR.
- The local workspace should normally be on `staging` before starting feature work. Always inspect `git status` first and preserve any user changes.

## Code and deployment safety

- Preserve Swedish text in UTF-8; use `Å`, `Ä`, and `Ö` correctly.
- Keep schema changes in a new Flyway migration. Do not mutate already-applied migration files.
- Do not seed demo data at application startup. `scripts/SeedDemoCalendar.java` is an explicit fixture importer only.
- Do not run tests or add tests unless the user asks. When asked to verify, use the project commands documented in README and report exactly what was run.
- Keep admin identities, OAuth credentials, database credentials, and deployment-protection bypass values out of documentation and tool output.
