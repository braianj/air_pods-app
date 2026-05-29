---
description: Check the latest GitHub Actions run for braianj/air_pods-app and report its status, the commit it's building, and the current published APK SHA on the `latest` release.
---

Use these tools to report concisely (under 10 lines):

1. The latest workflow runs (top 3): `Bash(curl -s "https://api.github.com/repos/braianj/air_pods-app/actions/runs?per_page=3" | python3 -c "import json,sys; d=json.load(sys.stdin); [print(r['head_sha'][:8], r['status'], r['conclusion'], '|', r['display_title'][:60]) for r in d.get('workflow_runs',[])]")` — if rate-limited, fall back to `mcp__github__list_commits` for SHA context only.
2. The currently published APK commit: call `mcp__github__list_releases` with `owner=braianj repo=air_pods-app perPage=1` and extract the SHA from the release body (it's after "from commit ").
3. Compare: if the latest commit on main matches the published SHA → build is up to date. If they differ and the run is `in_progress` → CI is currently building.

Report in Spanish. Format:
- "Último commit: <sha> — <title>"
- "Último CI: <status> (<conclusion>) en <sha>"
- "APK publicado: <sha>" plus "✅ al día" or "⏳ buildeando" or "❌ atrasado".
