# Xgy SMS Cloud Protocol v2

This document is the integration contract for the Android sender, Windows
receiver, and Cloudflare relay. The existing LAN v1 protocol remains unchanged.

## Goals

- Android sends every SMS over LAN and Cloudflare concurrently.
- Cloudflare stores only encrypted envelopes, including delivered history.
- Windows polls for pending envelopes and acknowledges them after local
  persistence and notification.
- Pairing keeps the six-digit user experience while deriving the encryption key
  from ephemeral P-256 ECDH keys that never leave the two devices.

## Encoding and crypto

- Binary fields use RFC 4648 base64url without padding.
- Device public keys are uncompressed SEC1 P-256 points: `04 || X(32) || Y(32)`.
- Shared secret: P-256 ECDH.
- HKDF-SHA256:
  - salt: `SHA256(UTF8("xgy-sms-v2:" + roomId))`
  - info: `UTF8("xgy-sms-room-key")`
  - output: 32 bytes
- Message encryption: AES-256-GCM with a random 12-byte nonce.
- AES-GCM output is `ciphertext || 16-byte tag`.
- AAD is UTF-8:
  `xgy-sms-v2\n{id}\n{roomId}\n{senderDeviceId}\n{targetDeviceId}`.

The encrypted plaintext JSON is:

```json
{
  "v": 2,
  "from": "10086",
  "text": "message body",
  "receivedAt": 1787400001000,
  "sim": 1,
  "device": "Android model"
}
```

## Pairing

Pair sessions expire after five minutes, permit at most five incorrect attempts,
and are removed after successful completion.

### `POST /v1/pair/start`

Receiver request. `deviceType` is `windows` for the Windows receiver and
`android_receiver` for an Android/Pad cloud receiver:

```json
{"deviceName":"DESKTOP","deviceType":"windows","publicKey":"..."}
```

Response:

```json
{"sessionId":"...","code":"583921","expiresAt":1787400300000}
```

### `POST /v1/pair/finish`

Android request:

```json
{"code":"583921","deviceName":"Xiaomi","deviceType":"android","publicKey":"..."}
```

Response contains Android credentials and the Windows peer key:

```json
{
  "roomId":"...",
  "deviceId":"phone_...",
  "token":"...",
  "peerDeviceId":"win_...",
  "peerPublicKey":"..."
}
```

### `GET /v1/pair/status?sessionId=...&code=...`

- `202`: pairing is still pending.
- `200`: response contains Windows credentials and the Android peer key using
  the same fields as `/v1/pair/finish`.
- `404` or `410`: invalid or expired session.

After Windows has durably saved the returned credentials it calls
`POST /v1/pair/confirm` with its new Bearer token and
`{"sessionId":"..."}`. The relay then deletes the temporary pairing session
and its recoverable plaintext tokens. Until confirmation, `pair/status` is
idempotent so a lost HTTP response does not force a new pairing ceremony.

Android receivers use the same status/confirm sequence. Each pairing creates a
two-device room. A sender phone may hold multiple independent sender links (one
per Windows/Android receiver) and encrypt one envelope per link while reusing
the same message UUID across LAN and every cloud target.

Pair finish/status responses in v0.6 may additionally contain `peerName`.
Older clients ignore this optional field.

## Messages

All device-authenticated requests use `Authorization: Bearer <device-token>`.

### `POST /v1/messages`

```json
{
  "roomId":"...",
  "senderDeviceId":"phone_...",
  "targetDeviceId":"win_...",
  "id":"uuid",
  "createdAt":1787400001000,
  "nonce":"...",
  "ciphertext":"..."
}
```

The relay validates membership and stores the envelope without decrypting it.

### `GET /v1/messages?roomId=...&deviceId=...&limit=100`

Returns undelivered encrypted envelopes for the authenticated target device,
oldest first. The relay accepts up to 100, but native v0.5 clients request 20
per page so a worst-case ciphertext response stays below their 2 MiB read cap.

### `POST /v1/ack`

```json
{"roomId":"...","deviceId":"win_...","messageIds":["..."]}
```

Acknowledged envelopes remain encrypted cloud history until the retention TTL
expires. The default retention is 30 days. Protocol v2 does not yet expose a
history-read route, so acknowledged rows are retention storage rather than a
client-visible backup/restore interface.

## Android device management extension

These v0.6 routes are additive; v2 pairing, message, and ACK behavior is
unchanged. Backup requests use a device-derived backup Bearer token, while
status and revoke requests use the existing per-link device token.

### `PUT /v1/device/backup`

Stores an opaque Android-encrypted backup:

```json
{"backupId":"backup_...","schema":1,"revision":1,"salt":"...","nonce":"...","ciphertext":"..."}
```

`salt` is 16 bytes and `nonce` is 12 bytes in unpadded base64url. Revisions
start at 1 and advance exactly by one. Repeating the same revision and blob is
idempotent; stale or skipped revisions return `409 backup_conflict`.

### `GET /v1/device/backup?backupId=...`

Returns the authenticated encrypted backup. A missing or unauthorized record
returns 404. A deleted tombstone returns 410. Revision-zero revived records
without a new backup also return 404.

### `DELETE /v1/device/backup?backupId=...`

Clears salt, nonce, ciphertext, and blob hash, but retains an authenticated
deletion tombstone so an old installation cannot silently recreate the backup.
`POST /v1/device/backup/revive` is the explicit user-confirmed inverse; it
resets the record to revision zero before a new revision-one upload.

### `GET /v1/device/status?roomId=...&deviceId=...`

Returns authenticated `active`, `deviceName`, `deviceType`, `peerActive`, and
`peerDeviceId`. Android validates both endpoints before restoring a link.

### `POST /v1/device/revoke`

Body: `{"roomId":"...","deviceId":"..."}`. The caller must authenticate as
that device. A room represents one two-device binding, so revocation disables
both endpoints; either token subsequently returns 401.

## LAN compatibility

The following v1 contract must not change:

- TCP `58123`, `POST /sms`, `X-Xgy-Key`
- Existing JSON fields `from`, `text`, `receivedAt`, `sim`, `device`
- v2 senders add an optional `id` UUID. The same ID is used by the concurrent
  cloud envelope so v2 receivers can deduplicate LAN/cloud delivery; v1
  receivers safely ignore the additional field.
- UDP `58124`
- discovery payload `XGY_SMS_V1|name|ip|port`
