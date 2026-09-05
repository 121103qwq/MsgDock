import {
  HttpError,
  constantTimeStringEqual,
  isSafeId,
  isSafeName,
  jsonResponse,
  randomId,
  randomToken,
  readJson,
  requireInteger,
  requireString,
  sha256Base64Url,
} from "./protocol";

export interface AccountEnv {
  DB: D1Database;
  DEVICE_BACKUP_PEPPER: string;
}

type AccountUser = {
  id: string;
  username: string;
  email: string | null;
};

type AccountDevice = {
  id: string;
  user_id: string;
  name: string;
  type: "android" | "windows" | "web";
  device_token_hash: string;
  last_seen_at: number | null;
  created_at: number;
  revoked_at: number | null;
};

type AccountSession = {
  id: string;
  user_id: string;
  token_hash: string;
  expires_at: number;
  created_at: number;
  revoked_at: number | null;
};

type AuthContext = {
  kind: "session" | "device";
  userId: string;
  session?: AccountSession;
  device?: AccountDevice;
};

const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const AUTH_RATE_WINDOW_MS = 5 * 60 * 1000;
const MAX_LOGIN_ATTEMPTS = 20;
const MAX_REGISTER_ATTEMPTS = 5;
// Production Workers caps Web Crypto PBKDF2 at 100,000 iterations.
// A domain-separated server-side HMAC also protects a D1-only disclosure.
const PASSWORD_ITERATIONS = 100_000;
const MAX_MESSAGE_BODY = 16 * 1024;

export async function accountFetch(request: Request, env: AccountEnv): Promise<Response> {
  try {
    const url = new URL(request.url);
    switch (`${request.method} ${url.pathname}`) {
      case "POST /api/v1/auth/register":
        return await register(request, env);
      case "POST /api/v1/auth/login":
        return await login(request, env);
      case "POST /api/v1/auth/logout":
        return await logout(request, env);
      case "GET /api/v1/me":
        return await me(request, env);
      case "GET /api/v1/devices":
        return await listDevices(request, env);
      case "POST /api/v1/devices":
        return await registerDevice(request, env);
      case "GET /api/v1/messages":
        return await getMessages(request, env, url);
      case "POST /api/v1/messages":
        return await postMessage(request, env);
      default:
        if (request.method === "DELETE" && url.pathname.startsWith("/api/v1/devices/")) {
          return await removeDevice(request, env, url);
        }
        throw new HttpError(404, "not_found");
    }
  } catch (error) {
    if (error instanceof HttpError) {
      return jsonResponse({ error: error.code }, error.status);
    }
    console.error("account request failed", error);
    return jsonResponse({ error: "internal_error" }, 500);
  }
}

async function register(request: Request, env: AccountEnv): Promise<Response> {
  await checkAuthRate(env.DB, request, "register");
  const body = await readJson(request);
  const username = normalizeUsername(body.username);
  const email = normalizeEmail(body.email, true);
  const password = requirePassword(body.password);
  const passwordHash = await hashPassword(password, env.DEVICE_BACKUP_PEPPER);
  const now = Date.now();
  const userId = randomId("usr_");

  try {
    await env.DB.prepare(
      "INSERT INTO users (id, username, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
    ).bind(userId, username, email, passwordHash, now).run();
  } catch (error) {
    if (isConstraintError(error)) throw new HttpError(409, "account_exists");
    throw error;
  }

  return await createSessionResponse(request, env.DB, {
    id: userId,
    username,
    email,
  }, now);
}

async function login(request: Request, env: AccountEnv): Promise<Response> {
  await checkAuthRate(env.DB, request, "login");
  const body = await readJson(request);
  const identifier = requireString(body.identifier ?? body.username ?? body.email, "invalid_identifier", 128).trim();
  const password = requirePassword(body.password);
  const row = await env.DB.prepare(
    "SELECT id, username, email, password_hash FROM users WHERE lower(username) = lower(?) OR lower(email) = lower(?) LIMIT 1",
  ).bind(identifier, identifier).first<{ id: string; username: string; email: string | null; password_hash: string }>();
  if (!row || !(await verifyPassword(password, row.password_hash, env.DEVICE_BACKUP_PEPPER))) {
    throw new HttpError(401, "invalid_credentials");
  }
  return await createSessionResponse(request, env.DB, {
    id: row.id,
    username: row.username,
    email: row.email,
  }, Date.now());
}

async function createSessionResponse(request: Request, db: D1Database, user: AccountUser, now: number): Promise<Response> {
  const token = randomToken();
  const tokenHash = await sha256Base64Url(token);
  const expiresAt = now + SESSION_TTL_MS;
  await db.prepare(
    "INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, NULL)",
  ).bind(randomId("ses_"), user.id, tokenHash, expiresAt, now).run();

  const native = request.headers.get("x-msgdock-client")?.toLowerCase() === "native";
  const responseBody: Record<string, unknown> = { user, expires_at: expiresAt };
  if (native) responseBody.session_token = token;
  const response = jsonResponse(responseBody, 200);
  if (native) return response;
  const headers = new Headers(response.headers);
  headers.set("set-cookie", sessionCookie(token, SESSION_TTL_MS));
  return new Response(response.body, { status: response.status, headers });
}

async function logout(request: Request, env: AccountEnv): Promise<Response> {
  const credential = extractCredential(request);
  if (credential) {
    const hash = await sha256Base64Url(credential);
    await env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")
      .bind(Date.now(), hash).run();
  }
  const response = jsonResponse({ logged_out: true });
  const headers = new Headers(response.headers);
  headers.set("set-cookie", sessionCookie("", 0));
  return new Response(response.body, { status: response.status, headers });
}

async function me(request: Request, env: AccountEnv): Promise<Response> {
  const auth = await requireSession(request, env.DB);
  return jsonResponse({ user: await userForId(env.DB, auth.userId) });
}

async function listDevices(request: Request, env: AccountEnv): Promise<Response> {
  const auth = await requireSession(request, env.DB);
  const result = await env.DB.prepare(
    "SELECT id, name, type, last_seen_at, created_at FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at ASC",
  ).bind(auth.userId).all<Pick<AccountDevice, "id" | "name" | "type" | "last_seen_at" | "created_at">>();
  return jsonResponse({ devices: result.results ?? [] });
}

async function registerDevice(request: Request, env: AccountEnv): Promise<Response> {
  const auth = await requireSession(request, env.DB);
  const body = await readJson(request);
  const name = body.name;
  const type = body.type;
  if (!isSafeName(name) || !isAccountDeviceType(type)) throw new HttpError(400, "invalid_device");
  const suppliedId = body.id === undefined ? null : requireString(body.id, "invalid_device_id", 128);
  if (suppliedId !== null && !isSafeId(suppliedId)) throw new HttpError(400, "invalid_device_id");

  const id = suppliedId ?? randomId("dev_");
  const token = randomToken();
  const tokenHash = await sha256Base64Url(token);
  const now = Date.now();
  const existing = await env.DB.prepare("SELECT * FROM devices WHERE id = ? AND user_id = ?")
    .bind(id, auth.userId).first<AccountDevice>();
  if (existing) {
    await env.DB.prepare(
      "UPDATE devices SET name = ?, type = ?, device_token_hash = ?, last_seen_at = ?, revoked_at = NULL WHERE id = ? AND user_id = ?",
    ).bind(name, type, tokenHash, now, id, auth.userId).run();
  } else {
    try {
      await env.DB.prepare(
        "INSERT INTO devices (id, user_id, name, type, device_token_hash, last_seen_at, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
      ).bind(id, auth.userId, name, type, tokenHash, now, now).run();
    } catch (error) {
      if (isConstraintError(error)) throw new HttpError(409, "device_exists");
      throw error;
    }
  }
  const device = await env.DB.prepare(
    "SELECT id, name, type, last_seen_at, created_at FROM devices WHERE id = ? AND user_id = ?",
  ).bind(id, auth.userId).first();
  return jsonResponse({ device, device_token: token }, existing ? 200 : 201);
}

async function removeDevice(request: Request, env: AccountEnv, url: URL): Promise<Response> {
  const auth = await requireSession(request, env.DB);
  const id = requireString(url.pathname.slice("/api/v1/devices/".length), "invalid_device_id", 128);
  if (!isSafeId(id)) throw new HttpError(400, "invalid_device_id");
  const result = await env.DB.prepare(
    "UPDATE devices SET revoked_at = ?, device_token_hash = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
  ).bind(Date.now(), `revoked_${randomToken()}`, id, auth.userId).run();
  if (!result.meta.changes) throw new HttpError(404, "device_not_found");
  return jsonResponse({ removed: true });
}

async function postMessage(request: Request, env: AccountEnv): Promise<Response> {
  const auth = await requireDevice(request, env.DB);
  const body = await readJson(request);
  const clientMessageId = requireString(body.client_message_id, "invalid_client_message_id", 128);
  const sender = requireString(body.sender, "invalid_sender", 128);
  const messageBody = requireString(body.body, "invalid_body", MAX_MESSAGE_BODY);
  const receivedAt = requireInteger(body.received_at, "invalid_received_at");
  if (!isSafeId(clientMessageId)) throw new HttpError(400, "invalid_client_message_id");
  const now = Date.now();
  await touchDevice(env.DB, auth.userId, auth.device!.id, now);
  const existing = await env.DB.prepare(
    "SELECT seq, source_device_id, sender, body, received_at FROM messages WHERE user_id = ? AND client_message_id = ? LIMIT 1",
  ).bind(auth.userId, clientMessageId).first<{ seq: number; source_device_id: string; sender: string; body: string; received_at: number }>();
  if (existing) {
    if (existing.source_device_id !== auth.device!.id || existing.sender !== sender || existing.body !== messageBody || Number(existing.received_at) !== receivedAt) {
      throw new HttpError(409, "message_conflict");
    }
    return jsonResponse({ seq: Number(existing.seq), deduplicated: true });
  }
  try {
    await env.DB.prepare(
      "INSERT INTO messages (user_id, source_device_id, client_message_id, sender, body, received_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).bind(auth.userId, auth.device!.id, clientMessageId, sender, messageBody, receivedAt, now).run();
  } catch (error) {
    if (isConstraintError(error)) {
      const retry = await env.DB.prepare("SELECT seq FROM messages WHERE user_id = ? AND client_message_id = ?")
        .bind(auth.userId, clientMessageId).first<{ seq: number }>();
      if (retry) return jsonResponse({ seq: Number(retry.seq), deduplicated: true });
    }
    throw error;
  }
  const created = await env.DB.prepare("SELECT seq FROM messages WHERE user_id = ? AND client_message_id = ?")
    .bind(auth.userId, clientMessageId).first<{ seq: number }>();
  if (!created) throw new HttpError(500, "message_not_stored");
  return jsonResponse({ seq: Number(created.seq), deduplicated: false }, 201);
}

async function getMessages(request: Request, env: AccountEnv, url: URL): Promise<Response> {
  const auth = await requireAccountCredential(request, env.DB);
  const rawLimit = url.searchParams.get("limit");
  const limit = rawLimit === null || rawLimit === "" ? 100 : Number(rawLimit);
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new HttpError(400, "invalid_limit");
  const afterValue = url.searchParams.get("after");
  const hasAfter = afterValue !== null && afterValue !== "";
  const after = hasAfter ? Number(afterValue) : 0;
  if (!Number.isSafeInteger(after) || after < 0) throw new HttpError(400, "invalid_after");
  if (auth.kind === "device") await touchDevice(env.DB, auth.userId, auth.device!.id, Date.now());

  let rows: Array<Record<string, unknown>>;
  if (hasAfter) {
    const result = await env.DB.prepare(
      `SELECT m.seq, m.client_message_id, m.sender, m.body, m.received_at, m.created_at,
              d.id AS source_id, d.name AS source_name, d.type AS source_type
       FROM messages m LEFT JOIN devices d ON d.id = m.source_device_id
       WHERE m.user_id = ? AND m.seq > ? ORDER BY m.seq ASC LIMIT ?`,
    ).bind(auth.userId, after, limit).all<Record<string, unknown>>();
    rows = result.results ?? [];
  } else {
    const result = await env.DB.prepare(
      `SELECT m.seq, m.client_message_id, m.sender, m.body, m.received_at, m.created_at,
              d.id AS source_id, d.name AS source_name, d.type AS source_type
       FROM messages m LEFT JOIN devices d ON d.id = m.source_device_id
       WHERE m.user_id = ? ORDER BY m.seq DESC LIMIT ?`,
    ).bind(auth.userId, limit).all<Record<string, unknown>>();
    rows = (result.results ?? []).reverse();
  }
  const messages = rows.map((row) => ({
    seq: Number(row.seq),
    client_message_id: row.client_message_id,
    sender: row.sender,
    body: row.body,
    received_at: Number(row.received_at),
    created_at: Number(row.created_at),
    source_device: row.source_id ? { id: row.source_id, name: row.source_name, type: row.source_type } : null,
  }));
  const nextSeq = messages.length > 0 ? messages[messages.length - 1].seq : after;
  return jsonResponse({ messages, next_seq: nextSeq });
}

async function requireSession(request: Request, db: D1Database): Promise<AuthContext> {
  const credential = extractCredential(request);
  if (!credential) throw new HttpError(401, "unauthorized");
  const tokenHash = await sha256Base64Url(credential);
  const session = await db.prepare(
    "SELECT id, user_id, token_hash, expires_at, created_at, revoked_at FROM sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ? LIMIT 1",
  ).bind(tokenHash, Date.now()).first<AccountSession>();
  if (!session) throw new HttpError(401, "unauthorized");
  return { kind: "session", userId: session.user_id, session };
}

async function requireDevice(request: Request, db: D1Database): Promise<AuthContext> {
  const credential = extractBearer(request);
  if (!credential) throw new HttpError(401, "device_token_required");
  const tokenHash = await sha256Base64Url(credential);
  const device = await db.prepare(
    "SELECT id, user_id, name, type, device_token_hash, last_seen_at, created_at, revoked_at FROM devices WHERE device_token_hash = ? AND revoked_at IS NULL LIMIT 1",
  ).bind(tokenHash).first<AccountDevice>();
  if (!device) throw new HttpError(401, "unauthorized");
  return { kind: "device", userId: device.user_id, device };
}

async function requireAccountCredential(request: Request, db: D1Database): Promise<AuthContext> {
  const cookie = extractCookie(request, "msgdock_session");
  const bearer = extractBearer(request);
  const credential = cookie ?? bearer;
  if (!credential) throw new HttpError(401, "unauthorized");
  const tokenHash = await sha256Base64Url(credential);
  const session = await db.prepare(
    "SELECT id, user_id, token_hash, expires_at, created_at, revoked_at FROM sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ? LIMIT 1",
  ).bind(tokenHash, Date.now()).first<AccountSession>();
  if (session) return { kind: "session", userId: session.user_id, session };
  const device = await db.prepare(
    "SELECT id, user_id, name, type, device_token_hash, last_seen_at, created_at, revoked_at FROM devices WHERE device_token_hash = ? AND revoked_at IS NULL LIMIT 1",
  ).bind(tokenHash).first<AccountDevice>();
  if (device) return { kind: "device", userId: device.user_id, device };
  throw new HttpError(401, "unauthorized");
}

function extractBearer(request: Request): string | null {
  const value = request.headers.get("authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(value);
  return match?.[1] ?? null;
}

function extractCredential(request: Request): string | null {
  return extractCookie(request, "msgdock_session") ?? extractBearer(request);
}

function extractCookie(request: Request, name: string): string | null {
  const header = request.headers.get("cookie") ?? "";
  for (const part of header.split(";")) {
    const [key, ...value] = part.trim().split("=");
    if (key === name) {
      const candidate = value.join("=");
      return /^[A-Za-z0-9_-]{40,64}$/.test(candidate) ? candidate : null;
    }
  }
  return null;
}

function sessionCookie(token: string, maxAge: number): string {
  return `msgdock_session=${token}; Max-Age=${Math.max(0, Math.floor(maxAge / 1000))}; Path=/; HttpOnly; Secure; SameSite=Lax`;
}

async function userForId(db: D1Database, userId: string): Promise<AccountUser> {
  const user = await db.prepare("SELECT id, username, email FROM users WHERE id = ? LIMIT 1").bind(userId).first<AccountUser>();
  if (!user) throw new HttpError(401, "unauthorized");
  return user;
}

async function touchDevice(db: D1Database, userId: string, deviceId: string, now: number): Promise<void> {
  await db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL")
    .bind(now, deviceId, userId).run();
}

async function checkAuthRate(db: D1Database, request: Request, action: "login" | "register"): Promise<void> {
  const ip = request.headers.get("CF-Connecting-IP")?.trim().slice(0, 128) || "unknown";
  const now = Date.now();
  const row = await db.prepare("SELECT window_started_at, login_count, register_count FROM auth_rate_limits WHERE ip = ?")
    .bind(ip).first<{ window_started_at: number; login_count: number; register_count: number }>();
  if (!row || now - Number(row.window_started_at) >= AUTH_RATE_WINDOW_MS) {
    await db.prepare(
      `INSERT INTO auth_rate_limits (ip, window_started_at, login_count, register_count)
       VALUES (?, ?, ?, ?) ON CONFLICT(ip) DO UPDATE SET window_started_at = excluded.window_started_at,
       login_count = excluded.login_count, register_count = excluded.register_count`,
    ).bind(ip, now, action === "login" ? 1 : 0, action === "register" ? 1 : 0).run();
    return;
  }
  const current = action === "login" ? Number(row.login_count) : Number(row.register_count);
  const limit = action === "login" ? MAX_LOGIN_ATTEMPTS : MAX_REGISTER_ATTEMPTS;
  if (current >= limit) throw new HttpError(429, `${action}_rate_limited`);
  await db.prepare(`UPDATE auth_rate_limits SET ${action}_count = ${action}_count + 1 WHERE ip = ?`).bind(ip).run();
}

function normalizeUsername(value: unknown): string {
  const username = requireString(value, "invalid_username", 64).trim();
  if (username.length < 3 || /[\u0000-\u001f\u007f\s]/.test(username)) throw new HttpError(400, "invalid_username");
  return username;
}

function normalizeEmail(value: unknown, optional: boolean): string | null {
  if (value === undefined || value === null || value === "") {
    if (optional) return null;
    throw new HttpError(400, "invalid_email");
  }
  const email = requireString(value, "invalid_email", 254).trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpError(400, "invalid_email");
  return email;
}

function requirePassword(value: unknown): string {
  const password = requireString(value, "invalid_password", 128);
  if (password.length < 8) throw new HttpError(400, "invalid_password");
  return password;
}

function isAccountDeviceType(value: unknown): value is "android" | "windows" | "web" {
  return value === "android" || value === "windows" || value === "web";
}

function isConstraintError(error: unknown): boolean {
  return error instanceof Error && /constraint|unique/i.test(error.message);
}

async function hashPassword(password: string, pepper: string): Promise<string> {
  const salt = new Uint8Array(16);
  crypto.getRandomValues(salt);
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: salt.buffer as ArrayBuffer, iterations: PASSWORD_ITERATIONS, hash: "SHA-256" },
    key,
    256,
  );
  return `pbkdf2-sha256-hmac-v1$${PASSWORD_ITERATIONS}$${base64Url(salt)}$${base64Url(await protectPasswordHash(new Uint8Array(derived), pepper))}`;
}

async function verifyPassword(password: string, encoded: string, pepper: string): Promise<boolean> {
  const parts = encoded.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256-hmac-v1") return false;
  const iterations = Number(parts[1]);
  if (iterations !== PASSWORD_ITERATIONS) return false;
  let salt: Uint8Array;
  let expected: Uint8Array;
  try {
    salt = decodeBase64Url(parts[2]);
    expected = decodeBase64Url(parts[3]);
  } catch {
    return false;
  }
  if (salt.length !== 16 || expected.length !== 32) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const derived = new Uint8Array(await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: salt.buffer as ArrayBuffer, iterations, hash: "SHA-256" },
    key,
    256,
  ));
  return constantTimeBytesEqual(await protectPasswordHash(derived, pepper), expected);
}

async function protectPasswordHash(derived: Uint8Array, pepper: string): Promise<Uint8Array> {
  if (!pepper) throw new Error("Password protection secret is not configured");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pepper),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const context = new TextEncoder().encode("MsgDock/password-hash/v1\u0000");
  const input = new Uint8Array(context.length + derived.length);
  input.set(context);
  input.set(derived, context.length);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, input));
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function decodeBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function constantTimeBytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  let difference = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) difference |= (left[index] ?? 0) ^ (right[index] ?? 0);
  return difference === 0;
}
