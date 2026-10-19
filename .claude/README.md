# .claude

Claude Code config for the Dokusho extensions repo. Same shape as the app repo.

| File | Loaded |
|------|--------|
| `/CLAUDE.md` | every turn: router, always-on rules, map |
| `docs/PLAN.md` | SOURCE and PUBLISH work |
| `.claude/rules/extension.md` | module anatomy, KeiSource contract, sanitising, accounts, themes, toolchain, debugging |
| `.claude/rules/publish.md` | CI pipeline, secrets, index repo, release checklist |
| `.claude/skills/ext-recon` | scout a site, write `docs/recon/<domain>.md` |
| `.claude/skills/ext-new` | scaffold and implement a source from a recon note |
| `.claude/skills/ext-ship-check` | scoped build, lint, checklist, device test |
| `.claude/agents/scout.md` | read-only repo search |
| hooks | `pre-push-guard.sh` on commit and push (keystores, println, secrets), `stop-kotlin-gate.sh` on stop (cheap greps, no Gradle) |

MCP: `context7` only. `.claude/settings.local.json` (gitignored) carries `JAVA_HOME` for JDK 21.
Keiyoushi's original contributing guide is kept at `docs/upstream/KEIYOUSHI_CONTRIBUTING.md`
as the toolchain reference; grep it rather than reading it whole.
