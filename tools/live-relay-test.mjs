import crypto from "node:crypto";

const relay = (process.argv[2] || "https://xgy-sms-relay.xgy2021sh.workers.dev").replace(/\/$/, "");
const receiverType = process.argv[3] || "windows";
if (!new Set(["windows", "android_receiver"]).has(receiverType)) {
  throw new Error(`Unsupported receiver type: ${receiverType}`);
}

async function request(path, { method = "GET", token = "", body } = {}) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  const response = await fetch(`${relay}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const value = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status} ${text}`);
  return { status: response.status, value };
}

const toB64 = (value) => Buffer.from(value).toString("base64url");

function deriveKey(ecdh, peerPublicKey, roomId) {
  const secret = ecdh.computeSecret(Buffer.from(peerPublicKey, "base64url"));
  const salt = crypto.createHash("sha256").update(`xgy-sms-v2:${roomId}`).digest();
  return Buffer.from(crypto.hkdfSync("sha256", secret, salt, Buffer.from("xgy-sms-room-key"), 32));
}

function aad(id, roomId, senderDeviceId, targetDeviceId) {
  return Buffer.from(["xgy-sms-v2", id, roomId, senderDeviceId, targetDeviceId].join("\n"));
}

const windows = crypto.createECDH("prime256v1");
const phone = crypto.createECDH("prime256v1");
windows.generateKeys();
phone.generateKeys();

const started = await request("/v1/pair/start", {
  method: "POST",
  body: { deviceName: `Live Test ${receiverType}`, deviceType: receiverType, publicKey: toB64(windows.getPublicKey()) },
});
const { sessionId, code } = started.value;

const finished = await request("/v1/pair/finish", {
  method: "POST",
  body: { code, deviceName: "Live Test Android", deviceType: "android", publicKey: toB64(phone.getPublicKey()) },
});
const phoneCredentials = finished.value;

const status = await request(`/v1/pair/status?sessionId=${encodeURIComponent(sessionId)}&code=${encodeURIComponent(code)}`);
const windowsCredentials = status.value;

const phoneKey = deriveKey(phone, phoneCredentials.peerPublicKey, phoneCredentials.roomId);
const windowsKey = deriveKey(windows, windowsCredentials.peerPublicKey, windowsCredentials.roomId);
if (!crypto.timingSafeEqual(phoneKey, windowsKey)) throw new Error("ECDH/HKDF room key mismatch");

await request("/v1/pair/confirm", {
  method: "POST",
  token: windowsCredentials.token,
  body: { sessionId },
});

const id = crypto.randomUUID();
const createdAt = Date.now();
const plaintextObject = {
  v: 2,
  from: "Xgy WAN synthetic test",
  text: "Cloudflare encrypted relay test code 424242",
  receivedAt: createdAt,
  sim: 1,
  device: "Synthetic Android",
};
const nonce = crypto.randomBytes(12);
const cipher = crypto.createCipheriv("aes-256-gcm", phoneKey, nonce);
cipher.setAAD(aad(id, phoneCredentials.roomId, phoneCredentials.deviceId, phoneCredentials.peerDeviceId));
const ciphertext = Buffer.concat([
  cipher.update(Buffer.from(JSON.stringify(plaintextObject))),
  cipher.final(),
  cipher.getAuthTag(),
]);

const envelope = {
  roomId: phoneCredentials.roomId,
  senderDeviceId: phoneCredentials.deviceId,
  targetDeviceId: phoneCredentials.peerDeviceId,
  id,
  createdAt,
  nonce: toB64(nonce),
  ciphertext: toB64(ciphertext),
};
await request("/v1/messages", { method: "POST", token: phoneCredentials.token, body: envelope });
const pending = await request(`/v1/messages?roomId=${encodeURIComponent(windowsCredentials.roomId)}&deviceId=${encodeURIComponent(windowsCredentials.deviceId)}&limit=100`, {
  token: windowsCredentials.token,
});
if (pending.value.messages.length !== 1 || pending.value.messages[0].id !== id) {
  throw new Error("Stored envelope was not returned to Windows");
}

const received = pending.value.messages[0];
const encrypted = Buffer.from(received.ciphertext, "base64url");
const decipher = crypto.createDecipheriv("aes-256-gcm", windowsKey, Buffer.from(received.nonce, "base64url"));
decipher.setAAD(aad(received.id, received.roomId, received.senderDeviceId, received.targetDeviceId));
decipher.setAuthTag(encrypted.subarray(encrypted.length - 16));
const decrypted = Buffer.concat([decipher.update(encrypted.subarray(0, -16)), decipher.final()]);
if (JSON.stringify(JSON.parse(decrypted.toString())) !== JSON.stringify(plaintextObject)) {
  throw new Error("Decrypted plaintext mismatch");
}

await request("/v1/ack", {
  method: "POST",
  token: windowsCredentials.token,
  body: { roomId: windowsCredentials.roomId, deviceId: windowsCredentials.deviceId, messageIds: [id] },
});
const afterAck = await request(`/v1/messages?roomId=${encodeURIComponent(windowsCredentials.roomId)}&deviceId=${encodeURIComponent(windowsCredentials.deviceId)}&limit=100`, {
  token: windowsCredentials.token,
});
if (afterAck.value.messages.length !== 0) throw new Error("ACK did not remove the message from the pending queue");

await request("/v1/device/revoke", {
  method: "POST",
  token: phoneCredentials.token,
  body: { roomId: phoneCredentials.roomId, deviceId: phoneCredentials.deviceId },
});
const revokedPeer = await fetch(
  `${relay}/v1/device/status?roomId=${encodeURIComponent(windowsCredentials.roomId)}&deviceId=${encodeURIComponent(windowsCredentials.deviceId)}`,
  { headers: { authorization: `Bearer ${windowsCredentials.token}` } },
);
if (revokedPeer.status !== 401) throw new Error(`Revoked peer remained active: HTTP ${revokedPeer.status}`);

console.log(JSON.stringify({
  ok: true,
  relay,
  protocol: 2,
  pairing: "confirmed",
  receiverType,
  encryption: "P-256 ECDH + HKDF-SHA256 + AES-256-GCM",
  storage: "stored, decrypted, acknowledged",
  cleanup: "paired room revoked on both endpoints",
}, null, 2));
