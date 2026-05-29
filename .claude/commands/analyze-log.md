---
description: Analyze an uploaded AirPods app log file. Extract battery-drain metrics, snapshot counts, packet stats, RSSI distribution, and flag anything anomalous.
argument-hint: [path-or-empty-for-most-recent-upload]
---

If `$ARGUMENTS` is empty, find the most recent log file under `/root/.claude/uploads/**/airpods_log_*.txt` (`Bash(find /root/.claude/uploads -name 'airpods_log_*.txt' -printf '%T@ %p\n' | sort -nr | head -1 | awk '{print $2}')`).

For that file, report concisely in Spanish:

1. **Build:** find the `installed=` line and report the SHA short.
2. **Battery drain:** find all `battery(1min)` and the final `battery@stop` line. Compute drain rate `drop ÷ uptime` in %/hour and call out whether `scanRunning`, `doze`, `powerSave` were true.
3. **Packet stats:** show the final `stats(10s)` line — which Continuity subtypes dominated. Comment if `0x07` count was 0 (no battery captures possible).
4. **Captured snapshots:** count `PARSED #N` lines. Show the L/R/case from the last 3 distinct PARSED lines.
5. **RSSI distribution:** for the user's snapshots (those that passed the `MIN_RSSI_DBM=-65` filter and got PARSED), show min/max/median. Note if anything weak (<-60) snuck through.
6. **Anomalies:** flag any `onScanFailed`, `read failed`, `SecurityException`, repeated `Service.stop()` cycles, or stale-data wipes.

Keep it tight — bullet form, no preamble. Don't dump raw log lines unless one is genuinely surprising.
