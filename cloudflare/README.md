# MsgDock Cloudflare Worker

This directory contains the MsgDock Worker. It keeps the existing `/v1/*`
Durable Object relay for protocol-v2 Android/Windows clients and adds the
account-based `/api/v1/*` API backed by Cloudflare D1. The static SPA in
`../web-ui` is served from the same Worker and is intended for
`https://msgdock.dpdns.org`.

The legacy relay stores encrypted envelopes only. The account API stores the
message body in D1 so the Web inbox can display it; this is the account-mode
privacy tradeoff and is separate from the legacy end-to-end encrypted relay.

## Routes

- `GET /health` — liveness response.
- `POST /v1/pair/start` — Windows or an Android cloud receiver starts a six-digit pairing session.
- `POST /v1/pair/finish` — Android completes pairing with the code.
- `GET /v1/pair/status` — the receiver polls for completion.
- `POST /v1/pair/confirm` — the authenticated receiver confirms credential receipt.
- `POST /v1/messages` — authenticated device stores an encrypted envelope.
- `GET /v1/messages` — authenticated target pulls unacknowledged envelopes.
- `POST /v1/ack` — authenticated target acknowledges envelopes.
- `GET/PUT/DELETE /v1/device/backup` — encrypted device backup storage with
  revisioned, idempotent writes and deletion tombstones.
- `POST /v1/device/backup/revive` — explicit user-confirmed tombstone revival.
- `GET /v1/device/status` — authenticated device and peer status.
- `POST /v1/device/revoke` — authenticated room-level revocation; disables
  both paired endpoints in the room.

## Account API

- `POST /api/v1/auth/register` — creates an account and starts a session.
- `POST /api/v1/auth/login` — accepts username or email plus password.
- `POST /api/v1/auth/logout` — revokes the current session.
- `GET /api/v1/me` — returns the current user.
- `POST /api/v1/devices` — session-authenticated device registration; returns
  the opaque `device_token` once.
- `GET /api/v1/devices` and `DELETE /api/v1/devices/:id` — own-device management.
- `POST /api/v1/messages` — device-token-only message upload with
  `client_message_id` idempotency.
- `GET /api/v1/messages?after=<seq>&limit=100` — session or device-token
  incremental inbox, ordered by `seq` and scoped to the authenticated user.

Browser login uses an HttpOnly, Secure, SameSite=Lax `msgdock_session` cookie.
Native callers send `X-MsgDock-Client: native` and receive a `session_token`
in the JSON response. Session and device tokens are separate opaque 32-byte
credentials; only SHA-256 token hashes are stored in D1.

Pairing codes expire after five minutes. A session allows at most five wrong
codes; the sixth request is treated as expired. Pairing starts are limited per
`CF-Connecting-IP`, unknown finish-code guesses are locked after five failures
in five minutes, and no more than 100 unexpired sessions are kept globally.
Message envelopes are retained for 30 days from acceptance and then deleted by
the Durable Object alarm.
Acknowledgement only removes an envelope from the pending poll; it does not
delete the encrypted history row. The current v2 API has no history-read route,
so acknowledged rows are not yet restorable from a client UI.

Message writes are idempotent on `(targetDeviceId, id)`. Repeating an Android
outbox request with the same id returns HTTP 200 and does not create a second
pending envelope.

Device backups are opaque encrypted blobs. The Worker stores only a peppered
HMAC of the backup bearer token, canonical nonce/ciphertext, revision metadata,
and a deletion tombstone. Configure the required `DEVICE_BACKUP_PEPPER` as a
Wrangler secret before deployment; it is intentionally absent from
`wrangler.jsonc`.
Backup PUT bodies must include integer `schema: 1`, a canonical 16-byte
base64url `salt`, `nonce`, and `ciphertext`; the relay returns the same salt on
GET so the client can derive its decryption key.

## Local checks

From this directory:

```text
npm install
npm test
npm run typecheck
```

The Vitest Miniflare binding supplies a deterministic test pepper. Production
deployment must provide the real secret with:

```text
npx wrangler secret put DEVICE_BACKUP_PEPPER
```

Run a local Worker with `npm run dev`. Wrangler provisions the SQLite Durable
Object from the `v1` migration in `wrangler.jsonc`.

## Deploy

Authenticate Wrangler in the account that owns the `xgy2021sh.workers.dev`
subdomain and `msgdock.dpdns.org`, create the D1 database, replace the
placeholder ID in `wrangler.jsonc`, then run:

```text
npx wrangler login
npx wrangler d1 create msgdock
npx wrangler d1 migrations apply msgdock --remote
npm run deploy
```

The existing `xgy-sms-relay` Worker already has `DEVICE_BACKUP_PEPPER`; a normal
same-name deployment preserves it. Check `npx wrangler secret list` and run
`npx wrangler secret put DEVICE_BACKUP_PEPPER` only if the secret is actually
missing. Replacing it would make existing encrypted device backups unreadable.

The custom-domain URL is:

`https://msgdock.dpdns.org`

The old workers.dev URL remains useful for compatibility testing until all
native clients use the account API.

Browser requests carrying an `Origin` header are rejected by default. If a
browser UI is explicitly needed, set the Worker variable `ALLOWED_ORIGINS` to a
comma-separated exact-origin allowlist before deployment; `*` is not accepted.
Native Android and Windows clients do not send an `Origin` header and are
unaffected.

Device tokens are random 256-bit bearer credentials. The relay stores the
token hash for authentication. During pairing, the issued tokens exist only in
the unexpired pairing session. Windows and Android receivers may retry
`/v1/pair/status` if a response is lost; after receiving credentials they must
`POST /v1/pair/confirm` with their new token and `{ "sessionId": "..." }`.
That authenticated confirmation deletes the session and temporary plaintext
tokens. Messages remain base64url-encoded ciphertext and are not inspected by
the Worker.
