# WebUI Phase 4 Closure Checklist

## Coverage

- `auth`: login, forced password change, current-password confirmation, short-lived confirmation reuse
- `config`: read/write for `BiliConfig.yml`, `BiliData.yml`, and `bot.yml`
- `conflict`: stale snapshot rejection for all three config scopes
- `runtime`: lifecycle state, platform readiness, restart request mode, counts
- `logs`: fixed whitelist sources, bounded tail windows, missing-source metadata
- `actions`: reload, graceful shutdown, restart request, and local fallback messaging
- `audit`: save success, validation failure, conflict rejection, risky actions

## Local Operator Path

1. log in
2. change password if required
3. inspect runtime snapshot
4. inspect and save each config scope
5. retry stale snapshots and verify conflict handling
6. switch between fixed log sources and tail windows
7. trigger reload, shutdown, and restart request
8. review the returned outcome, hints, and audit-safe metadata

## Expected Result

- Each step above is either locally debuggable in the WebUI shell or explicitly reported as unavailable by the backend contract.
