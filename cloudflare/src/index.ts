import { DurableObject } from "cloudflare:workers";
import { accountFetch } from "./account";
import {
  CLEANUP_INTERVAL_MS,
  encodeBase64Url,
  HttpError,
  MESSAGE_TTL_MS,
  PAIR_TTL_MS,
  bearerToken,
  canonicalCiphertext,
  canonicalNonce,
  canonicalPublicKey,
  canonicalSalt,
  constantTimeStringEqual,
  isDeviceType,
  isReceiverDeviceType,
  isSafeId,
  isSafeName,
  isSixDigitCode,
  jsonResponse,
  noContent,
  randomId,
  randomPairCode,
  randomToken,
  readJson,
  requireInteger,
  requireString,
  sha256Base64Url,
} from "./protocol";

export interface Env {
  RELAY: DurableObjectNamespace<Relay>;
  DB: D1Database;
  ASSETS?: Fetcher;
  ALLOWED_ORIGINS?: string;
  DEVICE_BACKUP_PEPPER: string;
}

interface PairSessionRow {
  session_id: string;
  code_hash: string;
  attempts: number;
  expires_at: number;
  windows_device_id: string;
  windows_name: string;
  windows_public_key: string;
  windows_token: string;
  room_id: string;
  phone_device_id: string | null;
  phone_name: string | null;
  phone_public_key: string | null;
  phone_token: string | null;
  completed_at: number | null;
}

interface DeviceRow {
  device_id: string;
  room_id: string;
  device_name: string;
  device_type: "android" | "windows" | "android_receiver";
  public_key: string;
  token_hash: string;
  active: number;
}

interface PairRateRow {
  ip: string;
  window_started_at: number;
  start_count: number;
  finish_failures: number;
  locked_until: number;
}

interface DeviceBackupRow {
  backup_id: string;
  token_hash: string;
  nonce: string;
  ciphertext: string;
  revision: number;
  created_at: number;
  updated_at: number;
  schema: number;
  salt: string;
  blob_hash: string;
  deleted_at: number | null;
}

interface BackupRateRow {
  ip: string;
  window_started_at: number;
  create_count: number;
}

const SCHEMA = `
  CREATE TABLE IF NOT EXISTS rooms (
    room_id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL,
    device_name TEXT NOT NULL,
    device_type TEXT NOT NULL,
    public_key TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    active INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS devices_room_idx ON devices(room_id, active);
  CREATE TABLE IF NOT EXISTS pair_sessions (
    session_id TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL,
    windows_device_id TEXT NOT NULL,
    windows_name TEXT NOT NULL,
    windows_public_key TEXT NOT NULL,
    windows_token TEXT NOT NULL,
    room_id TEXT NOT NULL,
    phone_device_id TEXT,
    phone_name TEXT,
    phone_public_key TEXT,
    phone_token TEXT,
    completed_at INTEGER
  );
  CREATE INDEX IF NOT EXISTS pair_sessions_code_idx ON pair_sessions(code_hash, expires_at);
  CREATE TABLE IF NOT EXISTS pair_rate_limits (
    ip TEXT PRIMARY KEY,
    window_started_at INTEGER NOT NULL,
    start_count INTEGER NOT NULL DEFAULT 0,
    finish_failures INTEGER NOT NULL DEFAULT 0,
    locked_until INTEGER NOT NULL DEFAULT 0
  );
  CREATE TABLE IF NOT EXISTS backup_rate_limits (
    ip TEXT PRIMARY KEY,
    window_started_at INTEGER NOT NULL,
    create_count INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS backup_rate_limits_window_idx
    ON backup_rate_limits(window_started_at);
  CREATE TABLE IF NOT EXISTS device_backups (
    backup_id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    nonce TEXT NOT NULL,
    ciphertext TEXT NOT NULL,
    revision INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    schema INTEGER NOT NULL DEFAULT 1,
    salt TEXT NOT NULL DEFAULT '',
    blob_hash TEXT NOT NULL DEFAULT '',
    deleted_at INTEGER
  );
  CREATE INDEX IF NOT EXISTS device_backups_updated_idx
    ON device_backups(updated_at);
  CREATE TABLE IF NOT EXISTS messages (
    room_id TEXT NOT NULL,
    sender_device_id TEXT NOT NULL,
    target_device_id TEXT NOT NULL,
    id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    nonce TEXT NOT NULL,
    ciphertext TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    acked_at INTEGER,
    PRIMARY KEY (room_id, id),
    UNIQUE (target_device_id, id)
  );
  CREATE INDEX IF NOT EXISTS messages_pending_idx
    ON messages(room_id, target_device_id, acked_at, created_at);
  CREATE INDEX IF NOT EXISTS messages_expiry_idx ON messages(expires_at);
`;

const PAIR_RATE_WINDOW_MS = 5 * 60 * 1000;
const MAX_PAIR_STARTS_PER_IP = 5;
const MAX_PAIR_FINISH_FAILURES_PER_IP = 5;
const MAX_UNEXPIRED_PAIR_SESSIONS = 100;
const BACKUP_RATE_WINDOW_MS = 5 * 60 * 1000;
const MAX_BACKUP_CREATES_PER_IP = 10;
const MAX_DEVICE_BACKUPS = 1000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!isAllowedOrigin(request, env)) {
      return withCors(jsonResponse({ error: "cors_forbidden" }, 403), request, env);
    }
    if (request.method === "OPTIONS") {
      return withCors(new Response(null, { status: 204 }), request, env);
    }

    const url = new URL(request.url);
    if (url.pathname === "/health" || url.pathname === "/v1/health") {
      return withCors(jsonResponse({
        ok: true,
        service: "msgdock",
        protocol: 2,
        features: ["account-d1-v1", "web-inbox-v1", "device-backup-v1", "device-status-v1", "device-revoke-v1"],
      }), request, env);
    }
    if (url.pathname.startsWith("/api/v1/")) {
      return withCors(await accountFetch(request, env), request, env);
    }
    if (!url.pathname.startsWith("/v1/")) {
      if (env.ASSETS && (request.method === "GET" || request.method === "HEAD")) {
        return env.ASSETS.fetch(request);
      }
      return withCors(jsonResponse({ error: "not_found" }, 404), request, env);
    }

    const id = env.RELAY.idFromName("xgy-sms-relay");
    const response = await env.RELAY.get(id).fetch(request);
    return withCors(response, request, env);
  },
};

export class Relay extends DurableObject<Env> {
  private readonly sql: any;
  private readonly ready: Promise<void>;
  private readonly relayEnv: Env;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.relayEnv = env;
    this.sql = ctx.storage.sql;
    this.ready = ctx.blockConcurrencyWhile(async () => {
      this.sql.exec(SCHEMA);
      if ((await ctx.storage.getAlarm()) === null) {
        await ctx.storage.setAlarm(Date.now() + CLEANUP_INTERVAL_MS);
      }
    });
  }

  async fetch(request: Request): Promise<Response> {
    await this.ready;
    try {
      const url = new URL(request.url);
      switch (`${request.method} ${url.pathname}`) {
        case "POST /v1/pair/start":
          return await this.pairStart(request);
        case "POST /v1/pair/finish":
          return await this.pairFinish(request);
        case "GET /v1/pair/status":
          return await this.pairStatus(url);
        case "POST /v1/pair/confirm":
          return await this.pairConfirm(request);
        case "POST /v1/messages":
          return await this.postMessage(request);
        case "GET /v1/messages":
          return await this.getMessages(request, url);
        case "POST /v1/ack":
          return await this.ackMessages(request);
        case "GET /v1/device/backup":
          return await this.getDeviceBackup(request, url);
        case "PUT /v1/device/backup":
          return await this.putDeviceBackup(request);
        case "DELETE /v1/device/backup":
          return await this.deleteDeviceBackup(request, url);
        case "POST /v1/device/backup/revive":
          return await this.reviveDeviceBackup(request);
        case "GET /v1/device/status":
          return await this.deviceStatus(request, url);
        case "POST /v1/device/revoke":
          return await this.revokeDevice(request);
        default:
          throw new HttpError(404, "not_found");
      }
    } catch (error) {
      if (error instanceof HttpError) {
        return jsonResponse({ error: error.code }, error.status);
      }
      console.error("relay request failed", error);
      return jsonResponse({ error: "internal_error" }, 500);
    }
  }

  async alarm(): Promise<void> {
    await this.ready;
    const now = Date.now();
    try {
      this.sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
      this.sql.exec("DELETE FROM pair_sessions WHERE expires_at <= ?", now);
      this.sql.exec("DELETE FROM devices WHERE active = 0 AND created_at <= ?", now - PAIR_TTL_MS);
      this.sql.exec("DELETE FROM pair_rate_limits WHERE window_started_at <= ? AND locked_until <= ?", now - PAIR_RATE_WINDOW_MS, now);
      this.sql.exec("DELETE FROM backup_rate_limits WHERE window_started_at <= ?", now - BACKUP_RATE_WINDOW_MS);
      this.sql.exec(
        "DELETE FROM rooms WHERE NOT EXISTS (SELECT 1 FROM devices WHERE devices.room_id = rooms.room_id)",
      );
    } finally {
      await this.ctx.storage.setAlarm(Date.now() + CLEANUP_INTERVAL_MS);
    }
  }

  private async pairStart(request: Request): Promise<Response> {
    const ip = clientIp(request);
    this.checkPairStartRate(ip);
    const now = Date.now();
    const activeSessions = first<{ count: number }>(
      this.sql.exec("SELECT COUNT(*) AS count FROM pair_sessions WHERE expires_at > ?", now),
    );
    if (Number(activeSessions?.count ?? 0) >= MAX_UNEXPIRED_PAIR_SESSIONS) {
      throw new HttpError(429, "pair_capacity");
    }
    const body = await readJson(request);
    const deviceName = body.deviceName;
    const deviceType = body.deviceType;
    if (!isSafeName(deviceName) || !isDeviceType(deviceType) || !isReceiverDeviceType(deviceType)) {
      throw new HttpError(400, "invalid_pair_start");
    }
    const publicKey = await canonicalPublicKey(body.publicKey);
    const expiresAt = now + PAIR_TTL_MS;
    const receiverDeviceId = randomId(deviceType === "windows" ? "win_" : "android_receiver_");

    let code = randomPairCode();
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const hash = await sha256Base64Url(code);
      const existing = first<PairSessionRow>(
        this.sql.exec("SELECT session_id FROM pair_sessions WHERE code_hash = ? AND expires_at > ? LIMIT 1", hash, now),
      );
      if (!existing) {
        const sessionId = randomId("pair_");
        const roomId = randomId("room_");
        const token = randomToken();
        const tokenHash = await sha256Base64Url(token);
        this.sql.exec("INSERT INTO rooms(room_id, created_at) VALUES (?, ?)", roomId, now);
        this.sql.exec(
          `INSERT INTO devices(device_id, room_id, device_name, device_type, public_key, token_hash, active, created_at)
           VALUES (?, ?, ?, ?, ?, ?, 0, ?)`,
          receiverDeviceId,
          roomId,
          deviceName,
          deviceType,
          publicKey,
          tokenHash,
          now,
        );
        this.sql.exec(
          `INSERT INTO pair_sessions(
             session_id, code_hash, attempts, expires_at, windows_device_id, windows_name,
             windows_public_key, windows_token, room_id
           ) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?)`,
          sessionId,
          hash,
          expiresAt,
          receiverDeviceId,
          deviceName,
          publicKey,
          token,
          roomId,
        );
        return jsonResponse({ sessionId, code, expiresAt }, 201);
      }
      code = randomPairCode();
    }
    throw new HttpError(503, "pair_unavailable");
  }

  private async pairFinish(request: Request): Promise<Response> {
    const ip = clientIp(request);
    this.checkPairFinishRate(ip);
    const body = await readJson(request);
    const code = body.code;
    if (!isSixDigitCode(code)) {
      throw new HttpError(400, "invalid_pair_code");
    }
    const deviceName = body.deviceName;
    const deviceType = body.deviceType;
    if (!isSafeName(deviceName) || !isDeviceType(deviceType) || deviceType !== "android") {
      throw new HttpError(400, "invalid_pair_finish");
    }
    const publicKey = await canonicalPublicKey(body.publicKey);
    const suppliedSessionId = body.sessionId;
    const sessionId = suppliedSessionId === undefined ? null : requireString(suppliedSessionId, "invalid_session_id", 128);
    const now = Date.now();
    const codeHash = await sha256Base64Url(code);
    const session = sessionId
      ? first<PairSessionRow>(this.sql.exec("SELECT * FROM pair_sessions WHERE session_id = ?", sessionId))
      : first<PairSessionRow>(
          this.sql.exec(
            "SELECT * FROM pair_sessions WHERE code_hash = ? AND expires_at > ? ORDER BY expires_at DESC LIMIT 1",
            codeHash,
            now,
          ),
        );

    if (!session) {
      this.recordPairFinishFailure(ip);
      throw new HttpError(401, "invalid_pair_code");
    }
    if (session.expires_at <= now) {
      this.removeExpiredSession(session);
      this.recordPairFinishFailure(ip);
      throw new HttpError(410, "pair_expired");
    }
    if (session.code_hash !== codeHash) {
      this.recordPairFailure(session);
      this.recordPairFinishFailure(ip);
      throw new HttpError(session.attempts + 1 >= 5 ? 410 : 401, session.attempts + 1 >= 5 ? "pair_expired" : "invalid_pair_code");
    }

    if (session.completed_at !== null && session.phone_device_id && session.phone_token && session.phone_public_key) {
      return jsonResponse({
        roomId: session.room_id,
        deviceId: session.phone_device_id,
        token: session.phone_token,
        peerDeviceId: session.windows_device_id,
        peerPublicKey: session.windows_public_key,
        peerName: session.windows_name,
      });
    }

    const phoneDeviceId = randomId("phone_");
    const phoneToken = randomToken();
    const phoneTokenHash = await sha256Base64Url(phoneToken);
    this.sql.exec(
      `INSERT INTO devices(device_id, room_id, device_name, device_type, public_key, token_hash, active, created_at)
       VALUES (?, ?, ?, 'android', ?, ?, 1, ?)`,
      phoneDeviceId,
      session.room_id,
      deviceName,
      publicKey,
      phoneTokenHash,
      now,
    );
    this.sql.exec("UPDATE devices SET active = 1 WHERE device_id = ?", session.windows_device_id);
    this.sql.exec(
      `UPDATE pair_sessions
       SET phone_device_id = ?, phone_name = ?, phone_public_key = ?, phone_token = ?, completed_at = ?
       WHERE session_id = ?`,
      phoneDeviceId,
      deviceName,
      publicKey,
      phoneToken,
      now,
      session.session_id,
    );
    return jsonResponse({
      roomId: session.room_id,
      deviceId: phoneDeviceId,
      token: phoneToken,
      peerDeviceId: session.windows_device_id,
      peerPublicKey: session.windows_public_key,
      peerName: session.windows_name,
    }, 201);
  }

  private async pairStatus(url: URL): Promise<Response> {
    const sessionId = url.searchParams.get("sessionId");
    const code = url.searchParams.get("code");
    if (!sessionId || !isSixDigitCode(code)) {
      throw new HttpError(400, "invalid_pair_status");
    }
    const session = first<PairSessionRow>(this.sql.exec("SELECT * FROM pair_sessions WHERE session_id = ?", sessionId));
    if (!session) {
      throw new HttpError(404, "pair_not_found");
    }
    const now = Date.now();
    if (session.expires_at <= now) {
      this.removeExpiredSession(session);
      throw new HttpError(410, "pair_expired");
    }
    const codeHash = await sha256Base64Url(code);
    if (session.code_hash !== codeHash) {
      this.recordPairFailure(session);
      throw new HttpError(session.attempts + 1 >= 5 ? 410 : 404, session.attempts + 1 >= 5 ? "pair_expired" : "pair_not_found");
    }
    if (session.completed_at === null || !session.phone_device_id || !session.phone_public_key) {
      return jsonResponse({ state: "pending" }, 202);
    }
    // The legacy pair_sessions column names are retained for SQLite schema
    // compatibility; the referenced device may be Windows or Android.
    const receiver = first<DeviceRow>(this.sql.exec("SELECT * FROM devices WHERE device_id = ? AND active = 1", session.windows_device_id));
    if (!receiver) {
      throw new HttpError(410, "pair_expired");
    }
    return jsonResponse({
      roomId: session.room_id,
      deviceId: receiver.device_id,
      token: session.windows_token,
      peerDeviceId: session.phone_device_id,
      peerPublicKey: session.phone_public_key,
      peerName: session.phone_name,
    });
  }

  private async pairConfirm(request: Request): Promise<Response> {
    const auth = await this.authorize(request);
    const body = await readJson(request);
    const sessionId = requireString(body.sessionId, "invalid_session_id", 128);
    const session = first<PairSessionRow>(
      this.sql.exec("SELECT * FROM pair_sessions WHERE session_id = ?", sessionId),
    );
    if (!session) {
      throw new HttpError(404, "pair_not_found");
    }
    if (session.expires_at <= Date.now()) {
      this.removeExpiredSession(session);
      throw new HttpError(410, "pair_expired");
    }
    if (session.completed_at === null || !session.phone_device_id || !session.phone_public_key) {
      throw new HttpError(409, "pair_pending");
    }
    if (auth.device_id !== session.windows_device_id || auth.room_id !== session.room_id) {
      throw new HttpError(403, "device_forbidden");
    }
    // The authenticated receiver has received the credentials from /status.
    // Consume the session only after this explicit confirmation.
    this.sql.exec("DELETE FROM pair_sessions WHERE session_id = ?", session.session_id);
    return jsonResponse({ confirmed: true });
  }

  private async postMessage(request: Request): Promise<Response> {
    const auth = await this.authorize(request);
    const body = await readJson(request);
    const roomId = requireString(body.roomId, "invalid_room_id", 128);
    const senderDeviceId = requireString(body.senderDeviceId, "invalid_sender_device_id", 128);
    const targetDeviceId = requireString(body.targetDeviceId, "invalid_target_device_id", 128);
    const id = requireString(body.id, "invalid_message_id", 128);
    const createdAt = requireInteger(body.createdAt, "invalid_created_at");
    if (!isSafeId(roomId) || !isSafeId(senderDeviceId) || !isSafeId(targetDeviceId) || !isSafeId(id)) {
      throw new HttpError(400, "invalid_message_id");
    }
    if (auth.room_id !== roomId || auth.device_id !== senderDeviceId || senderDeviceId === targetDeviceId) {
      throw new HttpError(403, "device_forbidden");
    }
    const now = Date.now();
    if (createdAt < now - MESSAGE_TTL_MS || createdAt > now + 10 * 60 * 1000) {
      throw new HttpError(400, "created_at_out_of_range");
    }
    const target = first<DeviceRow>(
      this.sql.exec("SELECT * FROM devices WHERE device_id = ? AND room_id = ? AND active = 1", targetDeviceId, roomId),
    );
    if (!target) {
      throw new HttpError(403, "target_forbidden");
    }
    const nonce = canonicalNonce(body.nonce);
    const ciphertext = canonicalCiphertext(body.ciphertext);
    const duplicate = first<{
      room_id: string;
      sender_device_id: string;
      target_device_id: string;
      id: string;
      created_at: number;
      nonce: string;
      ciphertext: string;
    }>(
      this.sql.exec(
        `SELECT room_id, sender_device_id, target_device_id, id, created_at, nonce, ciphertext
         FROM messages
         WHERE (target_device_id = ? AND id = ?) OR (room_id = ? AND id = ?)
         LIMIT 1`,
        targetDeviceId,
        id,
        roomId,
        id,
      ),
    );
    if (duplicate) {
      const sameEnvelope = duplicate.room_id === roomId
        && duplicate.sender_device_id === senderDeviceId
        && duplicate.target_device_id === targetDeviceId
        && duplicate.id === id
        && Number(duplicate.created_at) === createdAt
        && duplicate.nonce === nonce
        && duplicate.ciphertext === ciphertext;
      if (!sameEnvelope) {
        throw new HttpError(409, "message_conflict");
      }
      return jsonResponse({ stored: true, duplicate: true }, 200);
    }
    this.sql.exec(
      `INSERT INTO messages(room_id, sender_device_id, target_device_id, id, created_at, nonce, ciphertext, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      roomId,
      senderDeviceId,
      targetDeviceId,
      id,
      createdAt,
      nonce,
      ciphertext,
      now + MESSAGE_TTL_MS,
    );
    return jsonResponse({ stored: true, duplicate: false }, 201);
  }

  private async getMessages(request: Request, url: URL): Promise<Response> {
    const auth = await this.authorize(request);
    const roomId = requireString(url.searchParams.get("roomId"), "invalid_room_id", 128);
    const deviceId = requireString(url.searchParams.get("deviceId"), "invalid_device_id", 128);
    if (!isSafeId(roomId) || !isSafeId(deviceId) || auth.room_id !== roomId || auth.device_id !== deviceId) {
      throw new HttpError(403, "device_forbidden");
    }
    const rawLimit = url.searchParams.get("limit");
    const limit = rawLimit === null || rawLimit === "" ? 100 : Number(rawLimit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      throw new HttpError(400, "invalid_limit");
    }
    const rows = this.sql.exec(
      `SELECT room_id, sender_device_id, target_device_id, id, created_at, nonce, ciphertext
       FROM messages
       WHERE room_id = ? AND target_device_id = ? AND acked_at IS NULL AND expires_at > ?
       ORDER BY created_at ASC, id ASC LIMIT ?`,
      roomId,
      deviceId,
      Date.now(),
      limit,
    ) as Iterable<Record<string, unknown>>;
    const messages = [];
    for (const row of rows) {
      messages.push({
        roomId: row.room_id,
        senderDeviceId: row.sender_device_id,
        targetDeviceId: row.target_device_id,
        id: row.id,
        createdAt: row.created_at,
        nonce: row.nonce,
        ciphertext: row.ciphertext,
      });
    }
    return jsonResponse({ messages });
  }

  private async ackMessages(request: Request): Promise<Response> {
    const auth = await this.authorize(request);
    const body = await readJson(request);
    const roomId = requireString(body.roomId, "invalid_room_id", 128);
    const deviceId = requireString(body.deviceId, "invalid_device_id", 128);
    const ids = body.messageIds;
    if (!isSafeId(roomId) || !isSafeId(deviceId) || auth.room_id !== roomId || auth.device_id !== deviceId) {
      throw new HttpError(403, "device_forbidden");
    }
    if (!Array.isArray(ids) || ids.length < 1 || ids.length > 100 || ids.some((id) => !isSafeId(id))) {
      throw new HttpError(400, "invalid_message_ids");
    }
    const uniqueIds = [...new Set(ids as string[])];
    const now = Date.now();
    for (const id of uniqueIds) {
      this.sql.exec(
        "UPDATE messages SET acked_at = COALESCE(acked_at, ?) WHERE room_id = ? AND target_device_id = ? AND id = ?",
        now,
        roomId,
        deviceId,
        id,
      );
    }
    return jsonResponse({ acked: uniqueIds.length });
  }

  private async getDeviceBackup(request: Request, url: URL): Promise<Response> {
    const tokenHash = await this.backupTokenHash(request);
    const backupId = parseBackupId(url.searchParams.get("backupId"));
    const backup = first<DeviceBackupRow>(
      this.sql.exec("SELECT * FROM device_backups WHERE backup_id = ?", backupId),
    );
    if (!backup) {
      throw new HttpError(404, "backup_not_found");
    }
    if (!constantTimeStringEqual(backup.token_hash, tokenHash)) {
      throw new HttpError(404, "backup_not_found");
    }
    if (backup.deleted_at !== null) {
      throw new HttpError(410, "backup_deleted");
    }
    if (Number(backup.revision) < 1 || !backup.salt || !backup.nonce || !backup.ciphertext) {
      // A revived machine keeps its identity/tombstone row but has no usable
      // encrypted snapshot until the client uploads revision 1 again.
      throw new HttpError(404, "backup_not_found");
    }
    return jsonResponse({
      backupId: backup.backup_id,
      nonce: backup.nonce,
      ciphertext: backup.ciphertext,
      revision: Number(backup.revision),
      updatedAt: Number(backup.updated_at),
      schema: backup.schema,
      salt: backup.salt,
      blobHash: backup.blob_hash,
    });
  }

  private async putDeviceBackup(request: Request): Promise<Response> {
    const tokenHash = await this.backupTokenHash(request);
    const body = await readJson(request);
    const backupId = parseBackupId(requireString(body.backupId, "invalid_backup_id", 128));
    const schema = requireInteger(body.schema, "invalid_backup_schema");
    if (schema !== 1) {
      throw new HttpError(400, "invalid_backup_schema");
    }
    const revision = requireInteger(body.revision, "invalid_backup_revision");
    const salt = canonicalSalt(body.salt);
    const nonce = canonicalNonce(body.nonce);
    const ciphertext = canonicalCiphertext(body.ciphertext);
    const blobHash = await sha256Base64Url(`${schema}.${salt}.${nonce}.${ciphertext}`);
    if (revision < 1) {
      throw new HttpError(400, "invalid_backup_revision");
    }

    const now = Date.now();
    const existing = first<DeviceBackupRow>(
      this.sql.exec("SELECT * FROM device_backups WHERE backup_id = ?", backupId),
    );
    if (existing) {
      if (!constantTimeStringEqual(existing.token_hash, tokenHash)) {
        throw new HttpError(404, "backup_not_found");
      }
      if (existing.deleted_at !== null) {
        throw new HttpError(410, "backup_deleted");
      }
      const currentRevision = Number(existing.revision);
      if (
        revision === currentRevision
        && existing.blob_hash === blobHash
      ) {
        return jsonResponse({
          backupId,
          revision: currentRevision,
          updatedAt: Number(existing.updated_at),
          blobHash,
          duplicate: true,
        });
      }
      if (revision !== currentRevision + 1) {
        return jsonResponse({ error: "backup_conflict", currentRevision }, 409);
      }
      this.sql.exec(
        `UPDATE device_backups
         SET schema = ?, salt = ?, nonce = ?, ciphertext = ?, blob_hash = ?, revision = ?, updated_at = ?
         WHERE backup_id = ? AND token_hash = ? AND revision = ?`,
        schema,
        salt,
        nonce,
        ciphertext,
        blobHash,
        revision,
        now,
        backupId,
        tokenHash,
        currentRevision,
      );
      return jsonResponse({ backupId, revision, updatedAt: now });
    }

    if (revision !== 1) {
      return jsonResponse({ error: "backup_conflict", currentRevision: 0 }, 409);
    }
    const tokenOwner = first<{ backup_id: string }>(
      this.sql.exec("SELECT backup_id FROM device_backups WHERE token_hash = ?", tokenHash),
    );
    if (tokenOwner) {
      throw new HttpError(409, "backup_token_conflict");
    }
    const count = first<{ count: number }>(this.sql.exec("SELECT COUNT(*) AS count FROM device_backups"));
    if (Number(count?.count ?? 0) >= MAX_DEVICE_BACKUPS) {
      throw new HttpError(429, "backup_capacity");
    }
    this.checkBackupCreateRate(clientIp(request));
    this.sql.exec(
      `INSERT INTO device_backups(
         backup_id, token_hash, nonce, ciphertext, revision, created_at, updated_at,
         schema, salt, blob_hash, deleted_at
       ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, NULL)`,
      backupId,
      tokenHash,
      nonce,
      ciphertext,
      now,
      now,
      schema,
      salt,
      blobHash,
    );
    return jsonResponse({ backupId, revision: 1, createdAt: now, updatedAt: now, blobHash }, 201);
  }

  private async deleteDeviceBackup(request: Request, url: URL): Promise<Response> {
    const tokenHash = await this.backupTokenHash(request);
    const backupId = parseBackupId(url.searchParams.get("backupId"));
    const backup = first<DeviceBackupRow>(
      this.sql.exec("SELECT * FROM device_backups WHERE backup_id = ?", backupId),
    );
    if (!backup) {
      throw new HttpError(404, "backup_not_found");
    }
    if (!constantTimeStringEqual(backup.token_hash, tokenHash)) {
      throw new HttpError(404, "backup_not_found");
    }
    if (backup.deleted_at !== null) {
      throw new HttpError(410, "backup_deleted");
    }
    const now = Date.now();
    this.sql.exec(
      "UPDATE device_backups SET salt = '', nonce = '', ciphertext = '', blob_hash = '', deleted_at = ?, updated_at = ? WHERE backup_id = ? AND token_hash = ? AND deleted_at IS NULL",
      now,
      now,
      backupId,
      tokenHash,
    );
    return noContent();
  }

  private async reviveDeviceBackup(request: Request): Promise<Response> {
    const tokenHash = await this.backupTokenHash(request);
    const body = await readJson(request);
    const backupId = parseBackupId(requireString(body.backupId, "invalid_backup_id", 128));
    const schema = requireInteger(body.schema, "invalid_backup_schema");
    if (schema !== 1) {
      throw new HttpError(400, "invalid_backup_schema");
    }
    const backup = first<DeviceBackupRow>(
      this.sql.exec("SELECT * FROM device_backups WHERE backup_id = ?", backupId),
    );
    if (!backup || !constantTimeStringEqual(backup.token_hash, tokenHash)) {
      throw new HttpError(404, "backup_not_found");
    }
    if (backup.deleted_at === null) {
      throw new HttpError(409, "backup_not_deleted");
    }
    const now = Date.now();
    this.sql.exec(
      `UPDATE device_backups
       SET schema = ?, salt = '', nonce = '', ciphertext = '', revision = 0, updated_at = ?, blob_hash = '', deleted_at = NULL
       WHERE backup_id = ? AND token_hash = ? AND deleted_at IS NOT NULL`,
      schema,
      now,
      backupId,
      tokenHash,
    );
    return jsonResponse({ revived: true, backupId, revision: 0, updatedAt: now });
  }

  private async deviceStatus(request: Request, url: URL): Promise<Response> {
    const auth = await this.authorize(request);
    const roomId = requireString(url.searchParams.get("roomId"), "invalid_room_id", 128);
    const deviceId = requireString(url.searchParams.get("deviceId"), "invalid_device_id", 128);
    if (!isSafeId(roomId) || !isSafeId(deviceId) || auth.room_id !== roomId || auth.device_id !== deviceId) {
      throw new HttpError(403, "device_forbidden");
    }
    const peer = first<{
      device_id: string;
      device_name: string;
      device_type: DeviceRow["device_type"];
      active: number;
    }>(this.sql.exec(
      `SELECT device_id, device_name, device_type, active
       FROM devices
       WHERE room_id = ? AND device_id != ?
       ORDER BY active DESC, created_at DESC
       LIMIT 1`,
      roomId,
      deviceId,
    ));
    return jsonResponse({
      active: Number(auth.active) === 1,
      deviceName: auth.device_name,
      deviceType: auth.device_type,
      peerActive: Number(peer?.active ?? 0) === 1,
      peerDeviceId: peer?.device_id ?? null,
    });
  }

  private async revokeDevice(request: Request): Promise<Response> {
    const auth = await this.authorize(request);
    const body = await readJson(request);
    const roomId = requireString(body.roomId, "invalid_room_id", 128);
    const deviceId = requireString(body.deviceId, "invalid_device_id", 128);
    if (!isSafeId(roomId) || !isSafeId(deviceId) || auth.room_id !== roomId || auth.device_id !== deviceId) {
      throw new HttpError(403, "device_forbidden");
    }
    // A room is exactly one paired two-device link. Revoking it must disable
    // both endpoints so the peer cannot remain online as an orphaned link.
    this.sql.exec("UPDATE devices SET active = 0 WHERE room_id = ?", roomId);
    return jsonResponse({ revoked: true });
  }

  private async authorize(request: Request): Promise<DeviceRow> {
    const token = bearerToken(request);
    const tokenHash = await sha256Base64Url(token);
    const device = first<DeviceRow>(this.sql.exec("SELECT * FROM devices WHERE token_hash = ? AND active = 1", tokenHash));
    if (!device) {
      throw new HttpError(401, "unauthorized");
    }
    return device;
  }

  private async backupTokenHash(request: Request): Promise<string> {
    let token: string;
    try {
      token = bearerToken(request);
    } catch (error) {
      // Backup identifiers are opaque handles. Do not disclose whether a
      // handle exists to callers without a valid backup bearer credential.
      if (error instanceof HttpError && error.status === 401) {
        throw new HttpError(404, "backup_not_found");
      }
      throw error;
    }
    const pepper = this.relayEnv.DEVICE_BACKUP_PEPPER;
    if (!pepper) {
      throw new HttpError(503, "backup_unavailable");
    }
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(pepper),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const digest = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(token));
    return encodeBase64Url(new Uint8Array(digest));
  }

  private checkBackupCreateRate(ip: string): void {
    const now = Date.now();
    const row = first<BackupRateRow>(this.sql.exec("SELECT * FROM backup_rate_limits WHERE ip = ?", ip));
    if (!row || now - Number(row.window_started_at) >= BACKUP_RATE_WINDOW_MS) {
      this.sql.exec(
        `INSERT INTO backup_rate_limits(ip, window_started_at, create_count)
         VALUES (?, ?, 1)
         ON CONFLICT(ip) DO UPDATE SET window_started_at = excluded.window_started_at, create_count = 1`,
        ip,
        now,
      );
      return;
    }
    if (Number(row.create_count) >= MAX_BACKUP_CREATES_PER_IP) {
      throw new HttpError(429, "backup_create_rate_limited");
    }
    this.sql.exec("UPDATE backup_rate_limits SET create_count = create_count + 1 WHERE ip = ?", ip);
  }

  private checkPairStartRate(ip: string): void {
    const now = Date.now();
    const row = first<PairRateRow>(this.sql.exec("SELECT * FROM pair_rate_limits WHERE ip = ?", ip));
    if (!row) {
      this.sql.exec(
        "INSERT INTO pair_rate_limits(ip, window_started_at, start_count, finish_failures, locked_until) VALUES (?, ?, 1, 0, 0)",
        ip,
        now,
      );
      return;
    }
    if (now - Number(row.window_started_at) >= PAIR_RATE_WINDOW_MS) {
      this.sql.exec(
        "UPDATE pair_rate_limits SET window_started_at = ?, start_count = 1, finish_failures = 0, locked_until = 0 WHERE ip = ?",
        now,
        ip,
      );
      return;
    }
    if (Number(row.start_count) >= MAX_PAIR_STARTS_PER_IP) {
      throw new HttpError(429, "pair_start_rate_limited");
    }
    this.sql.exec("UPDATE pair_rate_limits SET start_count = start_count + 1 WHERE ip = ?", ip);
  }

  private checkPairFinishRate(ip: string): void {
    const now = Date.now();
    const row = first<PairRateRow>(this.sql.exec("SELECT * FROM pair_rate_limits WHERE ip = ?", ip));
    if (!row) {
      this.sql.exec(
        "INSERT INTO pair_rate_limits(ip, window_started_at, start_count, finish_failures, locked_until) VALUES (?, ?, 0, 0, 0)",
        ip,
        now,
      );
      return;
    }
    if (Number(row.locked_until) > now) {
      throw new HttpError(429, "pair_finish_rate_limited");
    }
    if (now - Number(row.window_started_at) >= PAIR_RATE_WINDOW_MS) {
      this.sql.exec(
        "UPDATE pair_rate_limits SET window_started_at = ?, start_count = 0, finish_failures = 0, locked_until = 0 WHERE ip = ?",
        now,
        ip,
      );
    }
  }

  private recordPairFinishFailure(ip: string): void {
    const now = Date.now();
    const row = first<PairRateRow>(this.sql.exec("SELECT * FROM pair_rate_limits WHERE ip = ?", ip));
    if (!row || now - Number(row.window_started_at) >= PAIR_RATE_WINDOW_MS) {
      this.sql.exec(
        `INSERT INTO pair_rate_limits(ip, window_started_at, start_count, finish_failures, locked_until)
         VALUES (?, ?, 0, 1, ?) ON CONFLICT(ip) DO UPDATE SET
           window_started_at = excluded.window_started_at,
           start_count = 0,
           finish_failures = 1,
           locked_until = excluded.locked_until`,
        ip,
        now,
        now + PAIR_RATE_WINDOW_MS,
      );
      return;
    }
    const failures = Number(row.finish_failures) + 1;
    const lockedUntil = failures >= MAX_PAIR_FINISH_FAILURES_PER_IP ? now + PAIR_RATE_WINDOW_MS : 0;
    this.sql.exec(
      "UPDATE pair_rate_limits SET finish_failures = ?, locked_until = ? WHERE ip = ?",
      failures,
      lockedUntil,
      ip,
    );
  }

  private recordPairFailure(session: PairSessionRow): void {
    const attempts = Number(session.attempts) + 1;
    if (attempts >= 5) {
      this.removeExpiredSession(session);
      return;
    }
    this.sql.exec("UPDATE pair_sessions SET attempts = ? WHERE session_id = ?", attempts, session.session_id);
  }

  private removeExpiredSession(session: PairSessionRow): void {
    this.sql.exec("DELETE FROM pair_sessions WHERE session_id = ?", session.session_id);
    if (session.completed_at === null) {
      this.sql.exec("DELETE FROM devices WHERE device_id = ? AND active = 0", session.windows_device_id);
      this.sql.exec(
        "DELETE FROM rooms WHERE room_id = ? AND NOT EXISTS (SELECT 1 FROM devices WHERE devices.room_id = rooms.room_id)",
        session.room_id,
      );
    }
  }
}

function first<T>(cursor: Iterable<T>): T | undefined {
  for (const row of cursor) {
    return row;
  }
  return undefined;
}

function clientIp(request: Request): string {
  const value = request.headers.get("CF-Connecting-IP")?.trim();
  return value && value.length <= 128 ? value : "unknown";
}

function parseBackupId(value: unknown): string {
  const backupId = requireString(value, "invalid_backup_id", 128);
  if (!isSafeId(backupId) || !backupId.startsWith("backup_")) {
    throw new HttpError(400, "invalid_backup_id");
  }
  return backupId;
}

function isAllowedOrigin(request: Request, env: Env): boolean {
  const origin = request.headers.get("origin");
  if (!origin) {
    return true;
  }
  const configured = allowedOrigins(env);
  return configured.includes(origin);
}

function withCors(response: Response, request: Request, env: Env): Response {
  const headers = new Headers(response.headers);
  const requestOrigin = request.headers.get("origin");
  const configured = allowedOrigins(env);
  if (requestOrigin && configured.includes(requestOrigin)) {
    headers.set("access-control-allow-origin", requestOrigin);
  } else {
    headers.delete("access-control-allow-origin");
  }
  headers.set("access-control-allow-methods", "GET, POST, PUT, DELETE, OPTIONS");
  headers.set("access-control-allow-headers", "Authorization, Content-Type, X-MsgDock-Client");
  headers.set("access-control-max-age", "86400");
  headers.append("vary", "Origin");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function allowedOrigins(env: Env): string[] {
  const configured = env.ALLOWED_ORIGINS?.split(",").map((value) => value.trim()).filter((value) => value && value !== "*") ?? [];
  if (!configured.includes("https://msgdock.dpdns.org")) configured.push("https://msgdock.dpdns.org");
  return configured;
}
