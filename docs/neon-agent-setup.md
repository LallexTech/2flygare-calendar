# Neon agent setup and session handoff

## Installed and verified

- Neon CLI 8.16.0 was installed globally using the official npm package. Node.js 24.12.0 meets Neon's setup requirements. CLI browser sign-in completed for the existing account.
- `.codex/config.toml` adds the project-level `Neon` MCP server at `https://mcp.neon.tech/mcp`, using OAuth. It contains no API key. The installer was restricted to Codex and this project.
- `.agents/skills/` contains `neon`, `neon-auth`, `neon-postgres`, and `neon-postgres-branches`, installed from the official Neon skills repository. `neon skills status` verified all four are up to date.
- `skills-lock.json` records the skill installations.
- Existing user-level Codex configuration, including Chrome DevTools and Node REPL, was preserved byte-for-byte. The existing `.env.local` was also preserved byte-for-byte during agent setup. Private configuration backup and pre-install hashes are under the ignored `.vercel/neon-agent-setup-backup/` directory.
- No `neon init`, project replacement, or automatic environment pull was used for agent setup. Existing Vercel and database configuration remains in place.

Start a fresh agent session in this project to load the new skills and MCP server. The MCP server may request its own OAuth sign-in on first use; CLI sign-in has already completed. Do not treat a registered MCP URL as proof that a live MCP connection is authenticated.

Sources: [Neon agent setup](https://neon.com/prompts/agent-setup.md), [Neon skill](https://neon.com/.well-known/agent-skills/neon/SKILL.md), [Codex MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

## Computer and browser control

The existing global Chrome DevTools MCP entry is enabled and its executable is available. The Windows Computer Use skill is already installed, but the desktop connection currently reports `Computer Use native pipe is unavailable`. No duplicate browser MCP, Windows helper, or security-setting change was installed. Recheck availability after restarting the agent application; do not claim desktop control until an actual connection succeeds.

## Application status after Google/planning setup

- Java 21 / Spring Boot, Thymeleaf and JavaScript, with esbuild and vis-timeline. Vercel beta containers remain the hosting target.
- Stable application URL: **https://2flygarecalender.vercel.app**. Preserve the requested spelling. The existing Vercel project name and local linkage remain `2flygare`.
- Alexander is the only configured initial administrator. His supplied email remains in Vercel configuration; Tobias and Tony await real addresses. Developer is informational. Bootstrap remains verified, transactional, and idempotent; unconfigured users remain pending until approval.
- The user's downloaded Google web client was read from their Downloads folder. Only `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` (Secret), and `GOOGLE_LOGIN_ENABLED` were added to Vercel Preview. No credential value or client JSON was copied into source code or printed. Other environment settings were preserved. `scripts/configure-google-login.py` supports safe repeat imports.
- Google-only application login replaces email links. Spring Security performs OIDC validation, state, nonce and PKCE. Provider subjects are bound to verified local email identities. Disabled email routes cannot deliver or redeem links in the deployment. Legacy mail/token tests remain isolated test fixtures.
- Required Google callback: `https://2flygarecalender.vercel.app/login/oauth2/code/google`. The downloaded JSON listed no callbacks. The connected Chrome browser was signed out of Google Cloud, so the owner was asked to sign in or register the callback. Do not claim a completed real-user Google login until that path is verified.
- The existing Neon application database is reused. No database or integration was replaced for this work. The separate Neon sign-in resource, its trusted domains, and existing `AUTH_` variables are retained, but are not used by deployed Google-only login.
- Schema migrations add employees, booking assignments/status/version, project colors, configurable workday settings and external identity bindings. No login users or roles are added by demo seeding.
- The primary workspace is a staff Gantt timeline, with dragging/resizing, 30-minute snapping and minimum duration, explicit overlap confirmation, and version checks. A linked Kanban view and follow-up reports share the same bookings. All projects count toward occupancy even when dimmed by a filter.
- The requested sample contains six projects, five named staff and 48 bookings for November 2?20, 2026. Demo upgrades add employee assignments, colors, and some spare capacity. Imports use the existing database and a transaction/advisory lock.
- The supplied logo remains the shortcut icon. A visible install action supports the browser prompt and manual Android/iOS instructions.
- Tests include H2 and real PostgreSQL security/planning contracts. Playwright uses only the loopback test fixture, and verifies actual timeline dragging and resizing, status movement, half-hour edits, reports, and phone layout. Private test sessions/screenshots stay under ignored `.vercel/`.

Read `README.md` for sign-in, bootstrap, configuration, deployments and tests. No new user-level Codex configuration, duplicate browser MCP, Windows helper, or desktop security change was made by the Google/planning work. Chrome DevTools MCP is available in the current session; native desktop control must still be checked separately before claiming it works.
