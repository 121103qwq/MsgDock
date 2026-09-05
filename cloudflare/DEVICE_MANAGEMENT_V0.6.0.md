# Xgy SMS Relay v0.6.0 device management

Implemented in the Cloudflare Worker without changing protocol-v2 message or
pairing envelopes.

## API

- `PUT /v1/device/backup` creates revision `1` or advances exactly one
  revision. The client supplies schema `1` and a canonical 16-byte base64url
  salt, which the relay stores unchanged. Repeating the same revision and blob
  is idempotent (`200`).
- `GET /v1/device/backup?backupId=...` reads the opaque encrypted backup.
- `DELETE /v1/device/backup?backupId=...` creates a deletion tombstone (`204`)
  and clears the encrypted blob, salt, nonce, and blob hash.
- `POST /v1/device/backup/revive` explicitly revives a tombstone and resets the
  revision to `0`; the caller must then write revision `1`.
- `GET /v1/device/status?roomId=...&deviceId=...` reports the authenticated
  device and its peer.
- `POST /v1/device/revoke` revokes the authenticated room-level link and
  disables both paired endpoints.

Backup bearer tokens are stored as HMAC-SHA256 values keyed by the required
`DEVICE_BACKUP_PEPPER`; the pepper is supplied only as a runtime secret. The
SQLite schema is idempotent and retains backup tombstones. Backup creation is
limited to 10 per Cloudflare client IP per five minutes and 1,000 rows
globally. Cleanup removes expired rate-limit rows only.

Pair finish/status responses now include optional `peerName`; old clients can
ignore the additional field. `/health` advertises `device-backup-v1`,
`device-status-v1`, and `device-revoke-v1`.

## Validation

```text
npm run typecheck  # passed
npm test           # 7 tests passed
npx wrangler deploy --dry-run  # passed
```

Production deployment completed with `DEVICE_BACKUP_PEPPER` configured as a
Cloudflare secret. Current version ID:

```text
118ca671-8e74-463d-a83c-258ea02d59a1
```

The public endpoint passed encrypted backup lifecycle and pair/message/ACK/
room-revoke synthetic checks without real device identifiers or SMS data.
