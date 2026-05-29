---
description: Watch the `latest` GitHub release until it publishes a new APK matching the given commit SHA (or the current HEAD if no arg). Notifies when done.
argument-hint: [commit-sha-7-chars]
---

Get the target SHA: if `$ARGUMENTS` is non-empty use the first arg; otherwise `Bash(git rev-parse --short=7 HEAD)` for the current HEAD.

Spawn a Monitor task that polls the release every 30 seconds until the body mentions the target SHA, then exits. The Monitor command:

```
target="$ARGUMENTS"
[ -z "$target" ] && target=$(git rev-parse --short=7 HEAD)
echo "watching for $target on latest release"
until curl -s -m 10 "https://api.github.com/repos/braianj/air_pods-app/releases/tags/latest" | grep -q "$target"; do
  sleep 30
done
echo "PUBLISHED: $target — https://github.com/braianj/air_pods-app/releases/download/latest/app-debug.apk"
```

Set `timeout_ms=900000` (15 minutes — CI usually finishes in 2-3) and `persistent=false`. Report the publish link when the monitor fires.
