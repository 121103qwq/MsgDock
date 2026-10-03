export const PAIR_TTL_MS = 5 * 60 * 1000;
export const MESSAGE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
export const CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000;
export const MAX_JSON_BYTES = 64 * 1024;
export const MAX_CIPHERTEXT_BYTES = 48 * 1024;

const BASE64URL_RE = /^[A-Za-z0-9_-]*$/;

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message = code,
  ) {
    super(message);
    this.name = "HttpError";
  }
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

export function noContent(status = 204): Response {
  return new Response(null, { status });
}

export function randomBytes(size: number): Uint8Array {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  return bytes;
}

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function decodeBase64Url(value: unknown): Uint8Array {
  if (typeof value !== "string" || value.length > 128 * 1024 || !BASE64URL_RE.test(value)) {
    throw new HttpError(400, "invalid_base64");
  }
  const padded = value + "=".repeat((4 - (value.length % 4)) % 4);
  let binary: string;
  try {
    binary = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
  } catch {
    throw new HttpError(400, "invalid_base64");
  }
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

export async function sha256Base64Url(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return encodeBase64Url(new Uint8Array(digest));
}

export function randomId(prefix: string): string {
  return `${prefix}${encodeBase64Url(randomBytes(16))}`;
}

export function randomToken(): string {
  return encodeBase64Url(randomBytes(32));
}

export function randomPairCode(): string {
  const value = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
  return value.toString().padStart(6, "0");
}

export function isSixDigitCode(value: unknown): value is string {
  return typeof value === "string" && /^[0-9]{6}$/.test(value);
}

export type DeviceType = "android" | "windows" | "android_receiver";
export type ReceiverDeviceType = "windows" | "android_receiver";

export function isDeviceType(value: unknown): value is DeviceType {
  return value === "android" || value === "windows" || value === "android_receiver";
}

export function isReceiverDeviceType(value: unknown): value is ReceiverDeviceType {
  return value === "windows" || value === "android_receiver";
}

export function isSafeName(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 64 && !/[\u0000-\u001f\u007f]/.test(value);
}

export function isSafeId(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 128 && /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value);
}

export async function canonicalPublicKey(value: unknown): Promise<string> {
  const bytes = decodeBase64Url(value);
  if (bytes.length !== 65 || bytes[0] !== 0x04) {
    throw new HttpError(400, "invalid_public_key");
  }
  try {
    // Importing the raw SEC1 point makes WebCrypto validate that it is an
    // actual point on P-256, rather than merely checking its length/prefix.
    await crypto.subtle.importKey(
      "raw",
      bytes.buffer as ArrayBuffer,
      { name: "ECDH", namedCurve: "P-256" },
      false,
      [],
    );
  } catch {
    throw new HttpError(400, "invalid_public_key");
  }
  return encodeBase64Url(bytes);
}

export function canonicalNonce(value: unknown): string {
  const bytes = decodeBase64Url(value);
  if (bytes.length !== 12) {
    throw new HttpError(400, "invalid_nonce");
  }
  return encodeBase64Url(bytes);
}

export function canonicalSalt(value: unknown): string {
  const bytes = decodeBase64Url(value);
  if (bytes.length !== 16) {
    throw new HttpError(400, "invalid_backup_salt");
  }
  return encodeBase64Url(bytes);
}

export function canonicalCiphertext(value: unknown): string {
  const bytes = decodeBase64Url(value);
  if (bytes.length < 16 || bytes.length > MAX_CIPHERTEXT_BYTES) {
    throw new HttpError(400, "invalid_ciphertext");
  }
  return encodeBase64Url(bytes);
}

export function bearerToken(request: Request): string {
  const value = request.headers.get("authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{20,256})$/.exec(value);
  if (!match) {
    throw new HttpError(401, "unauthorized");
  }
  return match[1];
}

export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const length = request.headers.get("content-length");
  if (length && Number.isFinite(Number(length)) && Number(length) > MAX_JSON_BYTES) {
    throw new HttpError(413, "body_too_large");
  }
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length > MAX_JSON_BYTES) {
    throw new HttpError(413, "body_too_large");
  }
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new HttpError(400, "invalid_json");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "invalid_json_object");
  }
  return value as Record<string, unknown>;
}

export function requireString(value: unknown, code: string, maxLength = 128): string {
  if (typeof value !== "string" || value.length < 1 || value.length > maxLength) {
    throw new HttpError(400, code);
  }
  return value;
}

export function requireInteger(value: unknown, code: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) {
    throw new HttpError(400, code);
  }
  return value;
}

export function constantTimeStringEqual(left: string, right: string): boolean {
  const leftBytes = new TextEncoder().encode(left);
  const rightBytes = new TextEncoder().encode(right);
  let difference = leftBytes.length ^ rightBytes.length;
  const length = Math.max(leftBytes.length, rightBytes.length);
  for (let index = 0; index < length; index += 1) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }
  return difference === 0;
}
