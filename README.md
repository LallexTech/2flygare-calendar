# 2 Flygare Kalender

Java 21 / Spring Boot 4, Thymeleaf, JavaScript and a vis-timeline resource planner, hosted on Vercel's beta container runtime. PostgreSQL on Neon stores application data and shared sessions. The frontend is built with esbuild; React is not required.

Stable address: **https://2flygarecalender.vercel.app**.

For the durable architecture and deployment context used by contributors and coding agents, see [AGENTS.md](AGENTS.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## Sign in with Google

Open the stable address and choose **Fortsätt med Google**. Sign in with a verified Gmail or Google Workspace account. No email link or separate application password is needed. Google controls its own sign-in challenges; this application does not add a separate 2FA enrollment step.

Google Cloud has separate web clients for Preview and Production. Each client needs its own **Authorized redirect URI**:

```
Preview:    https://2flygarecalender-pre.vercel.app/login/oauth2/code/google
Production: https://2flygarecalender.vercel.app/login/oauth2/code/google
```

The Preview client's Authorized JavaScript origin is:

```
https://2flygarecalender-pre.vercel.app
```

Configure these under Google Cloud → Google Auth Platform → Clients → the corresponding web client. If the Google project is in Testing, its Audience configuration must allow the people testing the app. Only `openid`, `email`, and `profile` scopes are requested; the application does not request access to Gmail messages or Google Calendar.

Each Vercel environment has its own server-side `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `GOOGLE_LOGIN_ENABLED=true`. Preview and Production use separate Google OAuth clients; each client secret is stored only as a Vercel Secret and is never bundled into browser JavaScript. Downloaded Google client JSON files stay outside the repository. To import or update Preview credentials without printing their values:

```powershell
python scripts/configure-google-login.py 'C:\path\to\client_secret.json'
```

This script verifies the linked Vercel project, preserves other environment variables, and adds or updates only the three Google settings in Preview. It does not edit Google's registered redirect URIs. Redeploy after changing Vercel settings. A downloaded JSON file may predate later changes in Google Cloud.

Spring Security handles the authorization-code flow, state, nonce, PKCE, and ID-token signature/issuer/audience validation. Only verified email claims from authoritative Gmail/Workspace identities are accepted. Other Google accounts with third-party email addresses are currently rejected because Google is not authoritative for continued ownership of those addresses. Provider subjects are bound to local accounts; a different subject cannot silently take over an existing email account. Disabled accounts cannot sign in. Permissions are checked against the current database on every protected request.

Email-link endpoints are disabled in the deployed configuration. Legacy token code is retained only for isolated regression/browser fixtures, enabled by the explicit test setting `app.legacy-test-login`; it is off by default and is not configured in Vercel.

## Initial administrators and approval

| Person | Role | Informational designation |
|---|---|---|
| Tobias Flygare | ADMIN | — |
| Tony Flygare | ADMIN | — |
| Alexander Sjögren | ADMIN | Developer / technical maintainer |

Supply real addresses using `INITIAL_ADMIN_TOBIAS_EMAIL`, `INITIAL_ADMIN_TONY_EMAIL`, and `INITIAL_ADMIN_ALEXANDER_EMAIL`. There are no operational email defaults. The current rollout configures Alexander alone; Tobias and Tony can be enabled when their real Google email addresses are supplied. At least one valid, distinct address is required. Names and the Developer flag never grant permissions.

On a successful verified Google sign-in, the application creates or locks the normalized email account. A matching configured bootstrap identity receives ADMIN and APPROVED in the same transaction. All other new accounts remain MEMBER/PENDING and see the approval waiting page. An existing administrator approves them under **Administration → Users**. Pending accounts cannot read planning data or use management APIs.

The bootstrap ledger records each identity slot exactly once. Repeated sign-ins and application restarts do not duplicate grants or restore privileges revoked by an administrator. A claimed slot cannot silently be rebound to a new configured email. Bootstrap accounts can be disabled but their ledger is retained. The last active administrator cannot be removed or demoted. All three administrators have identical dashboard and management permissions, including users, roles, groups, calendars, activities, and registration approval.

Google treats dots in a personal `@gmail.com` username as insignificant. Sign-ins that differ only by dots resolve to the same canonical local identity. `googlemail.com` is canonicalized to `gmail.com` as well. Plus-address suffixes remain meaningful and are not stripped. Workspace addresses retain their exact normalized address.

## Planning workspace

The primary view is a staff Gantt timeline. It opens in a one-day view from 06:00 to 18:00 on both phones and desktops. Use the range menu to switch to a week, three weeks, or five weeks; use zoom controls for shorter bookings. Project filters dim other projects so colleagues' occupied time remains visible. Staff filters limit the displayed rows. Green backgrounds show future available working time.

In the timeline, activities use the assigned employee's color and a top edge in the project's color. Administrators can assign employee colors under **Arbetstid**. The daily allocation table combines each employee and date in one column so start time, end time, and hours fit in the booking dialog on phones without horizontal swiping.

Administrators can create bookings with **Ny bokning** or double-click an empty timeline position. Click a booking to open its details. Drag the booking to move its time or transfer one assigned colleague to another row; changing time affects the whole assigned team. Drag either edge to resize. Moves snap to 30 minutes; the server rejects bookings shorter than 30 minutes. Overlapping active assignments require explicit double-booking confirmation. Version checks prevent a stale browser edit from overwriting a colleague's saved edit.

Bookings have Planerad, Pågår, Hindrad, Klar, and Avbokad statuses. The linked Kanban board supports moving cards between columns, and the detail dialog provides the same status control on phones. The follow-up view shows capacity, scheduled time, completed time, future spare time, and project completion counts. Completed and cancelled activities release reserved future time; cancelled activities are excluded from scheduled totals. These are planning figures, not a payroll time clock. Overlapping assignments can make scheduled hours exceed capacity.

**Arbetstid** configures a normal working day (default eight hours), work start, lunch, and individual employee overrides. Capacity uses Monday–Friday and Europe/Stockholm. Public holidays and individual leave are not automatically imported; reserve unavailable time as bookings. All project assignments count toward availability, including dimmed projects.

The explicit demo import is `scripts/SeedDemoCalendar.java`: six fictional Swedish construction projects, five employees (Tony, Tobias, Jesper, Magnus, Anders), and 48 bookings from 2–20 November 2026 within the requested three weeks. It creates no login accounts or roles. Run `java scripts/SeedDemoCalendar.java --check` to validate the fixture without a database. The live importer requires the PostgreSQL driver and application database credentials in process environment variables. It uses a transaction and advisory lock, reuses matching demo records, assigns staff, and includes some half-days to demonstrate spare capacity. Rerunning it against unchanged demo records creates no duplicates; it is an explicit sample-data tool, not an automatic startup seed.

## Home-screen shortcut

The supplied `logo2flygare.jpg` provides the Android, maskable, Apple touch, and favicon assets. **Lägg till på hemskärmen** offers the browser's install prompt when available and otherwise explains the manual browser menu. Android Chrome: ⋮ → Add to Home screen / Install app. Browser installation prompts are controlled by the browser. The shortcut uses the same secure sign-in and does not cache private planning pages for offline use. Regenerate icons with `java scripts/GenerateIcons.java` if the original logo changes.

## Configuration and deployment

### Where configuration lives

Vercel is the source of hosted runtime variables for the linked `2flygare` project. Manage them in **Settings > Environment Variables** or with `npx.cmd vercel env` from this linked folder. Production and Preview variables are separate. A variable change affects only a deployment created after the change; it does not update already-running deployments. To list only variable names and scopes, run `npx.cmd vercel env ls --environment production` or replace `production` with `preview`.

Google's web client ID, client secret, and login toggle are server-side variables. The client secret is a Vercel Secret; never put it in frontend code, a checked-in `.env` file, or a `NEXT_PUBLIC_` variable. The downloaded client JSON stays outside this repository. `scripts/configure-google-login.py` imports Google values without printing credentials. The authorized redirect URI is documented in **Sign in with Google** above.

The Vercel-managed Neon Postgres project is currently named `2flygare-preview`. It is managed through the Vercel integration, so Neon CLI does not allow renaming it. Its default `main` branch is the database used by Preview/Development. Vercel integration variables such as `DATABASE_URL`, `PGUSER`, and `PGPASSWORD` are scoped to Preview/Development. Spring Boot uses the separate `DATABASE_JDBC_URL` Secret and the database username/password variables.

A separate Neon branch named `production` was created from `main` on 2026-11-03. It is a point-in-time copy of the preview data and schema from that date. Production writes and preview writes are isolated after the branch was created; there is no automatic data merge between them. Production uses its own `DATABASE_JDBC_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` Secrets in Vercel. The JDBC URL uses a direct Neon endpoint and `sslmode=verify-full`, so Flyway can apply schema migrations at application startup over a validated TLS connection.

There is a second, older Neon resource named `2flygare-signin-preview` for Neon Auth. Its `AUTH_*` variables are retained in Preview/Development for compatibility, but this Spring Security Google sign-in flow does not use Neon Auth or its email delivery service. Do not confuse those `AUTH_*` variables with the application's database credentials.

`.env.local` is for local development only. Vercel's `env pull` overwrites its target and does not return hosted Secrets, so use a separate backup before pulling if the file has local changes. Spring Boot does not automatically load `.env.local`; use Vercel's environment runner or explicitly load local variables. `.env.example` documents variable names without credentials. Neon CLI authentication and Neon agent skills are developer tooling, not runtime secrets.

### Preview and Production

Preview and Development use `APP_ENVIRONMENT=preview` or `APP_ENVIRONMENT=development` to show a translucent DEV watermark across all application pages and dialogs. The watermark does not intercept clicks. Set this variable only in the matching Vercel scopes; local Spring Boot runs must load it explicitly. An unset value defaults to production, and `VERCEL_ENV=production` always disables the watermark. Production does not include the watermark stylesheet in its pages.

Vercel creates separate deployments for Preview and Production. Preview is for review and uses Preview-scoped environment variables; Production is the user-facing deployment and uses Production-scoped values. An alias is only a hostname mapping: it does not change a deployment's target or its environment.

Current state (2026-11-03):

- `https://2flygarecalender-pre.vercel.app` is assigned to the Git branch `staging` and follows that branch's latest **Preview** deployment. The `2flygare` Vercel project is connected to `LallexTech/2flygare-calendar`; pushes to non-production branches create Preview deployments, while changes merged to `main` create Production deployments. The stable `-pre` alias has a Vercel Deployment Protection exception, so visitors reach the app's Google sign-in directly. Other generated Preview URLs remain protected.
- `https://2flygarecalender.vercel.app` is a project domain assigned to **Production**. It follows the latest Production deployment and sends visitors directly to the app's Google sign-in; no Vercel sign-in gate is shown there.
- The older `https://2flygare.vercel.app` remains Vercel's default Production domain. The preferred user-facing Production address is `2flygarecalender.vercel.app`.
- A new Production deployment was published on 2026-11-03. Its startup migration succeeded against the separate Neon `production` branch. Production and Preview data remain isolated.
- The team remains on the Hobby plan for the owner's private evaluation. Revisit the Vercel plan before using the app for ongoing company operations or adding employees; Vercel's Hobby use is limited to non-commercial personal projects.
- Vercel Deployment Protection and application sign-in are separate. The two stable domains bypass Vercel's login and use the app's Google OAuth, account approval, and role checks. Random Preview deployment URLs remain behind Vercel Authentication. Unapproved registrations stay pending.

The Google OAuth client callback is `https://2flygarecalender.vercel.app/login/oauth2/code/google`. The Production hostname follows the latest Production deployment. Feature branches and pull requests get their own generated Preview URLs; the stable `-pre` alias follows `staging` automatically after a Git-triggered deployment.

Vercel does not copy Preview variables into Production automatically. Production has its own Google client settings, independent Neon branch connection, `APP_BASE_URL`, `SPRING_PROFILES_ACTIVE=vercel`, `PORT=8080`, and Alexander's known bootstrap email. Only Alexander's email is currently configured; do not invent Tobias's or Tony's email addresses. They can be added after their real Google addresses are supplied.

For environment inventory, list names without printing secret values:

```powershell
npx.cmd vercel env ls --environment preview --scope alexsjogren-7420s-projects
npx.cmd vercel env ls --environment production --scope alexsjogren-7420s-projects
```

The Dockerfile builds frontend assets with Node 24, packages Java with Maven, and runs Java 21 as an unprivileged user. It excludes local environment files and tooling credentials. Flyway applies versioned schema migrations on startup. Spring's connection pool is limited to four connections. Shared JDBC sessions support multiple instances, with Secure/HttpOnly/SameSite=Lax cookies and a thirty-minute idle timeout. CSRF protects writes. API access requires approval; management requires ADMIN. Forwarded headers are trusted only in the explicit Vercel profile. No persistent container filesystem or background bootstrap job is required.

See [Neon agent setup](docs/neon-agent-setup.md) for the installed CLI/skills and the state of Neon MCP configuration.

## Verification and local browser fixture

```powershell
npm.cmd run build
.\mvnw.cmd test
.\mvnw.cmd -Ppostgres-test test
```

Tests cover all three bootstrap identities, approval, current permission checks, CSRF, shared sessions, idempotence, revocation, Google subject binding, OAuth state/nonce/PKCE, disabled email endpoints, minimum durations, booking conflicts, concurrent versions, status changes, and capacity. The PostgreSQL profile repeats the security and planning contracts against a temporary real PostgreSQL process. Fixtures use reserved `.test` addresses, never operational defaults.

`PlannerBrowserFixture` in test sources starts a loopback-only isolated H2 application on port 19876 and writes a local test sign-in link under ignored `.vercel/`. It is never included in the production JAR. `scripts/verify-planner.mjs` verifies creation, timeline move and resize, Kanban status, half-hour editing, reports, and a phone viewport using Playwright. Its cleanup is restricted to the fixture port. The fixture exits when `.vercel/ui-done` is created or after ten minutes. Local screenshots and test sessions stay under ignored `.vercel/`; never use those sessions on a deployed app.

See [Neon agent setup](docs/neon-agent-setup.md) for installed CLI, skills, MCP configuration, and preserved configuration.
