import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Public points from PROTOCOL_V2_TEST_VECTOR.json. These are real, on-curve
// P-256 points rather than length-only placeholders.
const REAL_PUBLIC_KEY_A = "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
const REAL_PUBLIC_KEY_B = "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E";

function validP256Key(): string {
  const hex = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5";
  const bytes = new Uint8Array(hex.match(/../g)!.map((part) => Number.parseInt(part, 16)));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function invalidP256Key(): string {
  const bytes = new Uint8Array(65).fill(1);
  bytes[0] = 4;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function b64(seed: number, size: number): string {
  const bytes = new Uint8Array(size).fill(seed);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function json(response: Response): Promise<any> {
  return response.json();
}

async function post(path: string, body: unknown, token?: string, ip?: string): Promise<Response> {
  return SELF.fetch(`https://relay.test${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(ip ? { "CF-Connecting-IP": ip } : {}),
    },
    body: JSON.stringify(body),
  });
}

async function put(path: string, body: unknown, token?: string, ip?: string): Promise<Response> {
  return SELF.fetch(`https://relay.test${path}`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(ip ? { "CF-Connecting-IP": ip } : {}),
    },
    body: JSON.stringify(body),
  });
}

async function remove(path: string, token?: string): Promise<Response> {
  return SELF.fetch(`https://relay.test${path}`, {
    method: "DELETE",
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });
}

describe("xgy sms relay", () => {
  it("pairs devices, stores encrypted envelopes idempotently, and ACKs them", async () => {
    const startResponse = await post("/v1/pair/start", {
      deviceName: "test-windows",
      deviceType: "windows",
      publicKey: REAL_PUBLIC_KEY_A,
    });
    expect(startResponse.status).toBe(201);
    const start = await json(startResponse);

    const pending = await SELF.fetch(`https://relay.test/v1/pair/status?sessionId=${start.sessionId}&code=${start.code}`);
    expect(pending.status).toBe(202);

    const finishResponse = await post("/v1/pair/finish", {
      code: start.code,
      deviceName: "test-phone",
      deviceType: "android",
      publicKey: REAL_PUBLIC_KEY_B,
    });
    expect(finishResponse.status).toBe(201);
    const phone = await json(finishResponse);

    const statusResponse = await SELF.fetch(`https://relay.test/v1/pair/status?sessionId=${start.sessionId}&code=${start.code}`);
    expect(statusResponse.status).toBe(200);
    const windows = await json(statusResponse);
    expect(windows.roomId).toBe(phone.roomId);
    expect(windows.peerDeviceId).toBe(phone.deviceId);
    expect(windows.peerName).toBe("test-phone");
    const repeatedStatus = await SELF.fetch(`https://relay.test/v1/pair/status?sessionId=${start.sessionId}&code=${start.code}`);
    expect(repeatedStatus.status).toBe(200);

    const crossDeviceConfirm = await post(
      "/v1/pair/confirm",
      { sessionId: start.sessionId },
      phone.token,
    );
    expect(crossDeviceConfirm.status).toBe(403);
    const confirm = await post(
      "/v1/pair/confirm",
      { sessionId: start.sessionId },
      windows.token,
    );
    expect(confirm.status).toBe(200);
    expect((await json(confirm)).confirmed).toBe(true);
    const consumedStatus = await SELF.fetch(`https://relay.test/v1/pair/status?sessionId=${start.sessionId}&code=${start.code}`);
    expect(consumedStatus.status).toBe(404);
    const repeatedConfirm = await post(
      "/v1/pair/confirm",
      { sessionId: start.sessionId },
      windows.token,
    );
    expect(repeatedConfirm.status).toBe(404);

    const message = {
      roomId: phone.roomId,
      senderDeviceId: phone.deviceId,
      targetDeviceId: windows.deviceId,
      id: "message-1",
      createdAt: Date.now(),
      nonce: b64(3, 12),
      ciphertext: b64(4, 16),
    };
    const first = await post("/v1/messages", message, phone.token);
    expect(first.status).toBe(201);
    const retry = await post("/v1/messages", message, phone.token);
    expect(retry.status).toBe(200);
    expect((await json(retry)).duplicate).toBe(true);
    const conflict = await post("/v1/messages", { ...message, ciphertext: b64(5, 16) }, phone.token);
    expect(conflict.status).toBe(409);

    const received = await SELF.fetch(
      `https://relay.test/v1/messages?roomId=${phone.roomId}&deviceId=${windows.deviceId}&limit=100`,
      { headers: { authorization: `Bearer ${windows.token}` } },
    );
    expect(received.status).toBe(200);
    expect((await json(received)).messages).toHaveLength(1);

    const ack = await post("/v1/ack", { roomId: phone.roomId, deviceId: windows.deviceId, messageIds: ["message-1"] }, windows.token);
    expect(ack.status).toBe(200);
    const empty = await SELF.fetch(
      `https://relay.test/v1/messages?roomId=${phone.roomId}&deviceId=${windows.deviceId}`,
      { headers: { authorization: `Bearer ${windows.token}` } },
    );
    expect((await json(empty)).messages).toHaveLength(0);
  });

  it("stores revisioned device backups, hides them from wrong tokens, and keeps delete tombstones", async () => {
    const backupId = "backup_test_v060";
    const token = "backup-token-v060-aaaaaaaaaaaaaaaaaaaa";
    const wrongToken = "backup-token-v060-bbbbbbbbbbbbbbbbbbbb";
    const payload = {
      backupId,
      schema: 1,
      revision: 1,
      salt: b64(7, 16),
      nonce: b64(8, 12),
      ciphertext: b64(9, 32),
    };
    const created = await put("/v1/device/backup", payload, token, "203.0.113.60");
    expect(created.status).toBe(201);
    const createdBody = await json(created);
    expect(createdBody.revision).toBe(1);
    expect(createdBody.blobHash).toEqual(expect.any(String));
    const missingAuth = await SELF.fetch(`https://relay.test/v1/device/backup?backupId=${backupId}`);
    expect(missingAuth.status).toBe(404);

    const read = await SELF.fetch(`https://relay.test/v1/device/backup?backupId=${backupId}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(read.status).toBe(200);
    const readBody = await json(read);
    expect(readBody.ciphertext).toBe(payload.ciphertext);
    expect(readBody.salt).toBe(payload.salt);

    const duplicate = await put("/v1/device/backup", payload, token, "203.0.113.60");
    expect(duplicate.status).toBe(200);
    expect((await json(duplicate)).duplicate).toBe(true);

    const conflict = await put(
      "/v1/device/backup",
      { ...payload, ciphertext: b64(10, 32) },
      token,
      "203.0.113.60",
    );
    expect(conflict.status).toBe(409);
    expect((await json(conflict)).currentRevision).toBe(1);

    const advanced = await put(
      "/v1/device/backup",
      { ...payload, revision: 2, ciphertext: b64(10, 32) },
      token,
      "203.0.113.60",
    );
    expect(advanced.status).toBe(200);
    expect((await json(advanced)).revision).toBe(2);

    const hidden = await SELF.fetch(`https://relay.test/v1/device/backup?backupId=${backupId}`, {
      headers: { authorization: `Bearer ${wrongToken}` },
    });
    expect(hidden.status).toBe(404);
    const wrongDelete = await remove(`/v1/device/backup?backupId=${backupId}`, wrongToken);
    expect(wrongDelete.status).toBe(404);

    const deleted = await remove(`/v1/device/backup?backupId=${backupId}`, token);
    expect(deleted.status).toBe(204);
    const gone = await SELF.fetch(`https://relay.test/v1/device/backup?backupId=${backupId}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(gone.status).toBe(410);
    const blockedPut = await put("/v1/device/backup", { ...payload, revision: 3 }, token);
    expect(blockedPut.status).toBe(410);

    const revived = await post("/v1/device/backup/revive", { backupId, schema: 1 }, token);
    expect(revived.status).toBe(200);
    expect((await json(revived)).revision).toBe(0);
    const revivedRead = await SELF.fetch(`https://relay.test/v1/device/backup?backupId=${backupId}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(revivedRead.status).toBe(404);
    const recreated = await put("/v1/device/backup", payload, token);
    expect(recreated.status).toBe(200);
    expect((await json(recreated)).revision).toBe(1);
  });

  it("reports peer status and supports authenticated self-revocation", async () => {
    const startResponse = await post("/v1/pair/start", {
      deviceName: "status-windows-v060",
      deviceType: "windows",
      publicKey: REAL_PUBLIC_KEY_A,
    });
    const start = await json(startResponse);
    const finishResponse = await post("/v1/pair/finish", {
      code: start.code,
      deviceName: "status-phone-v060",
      deviceType: "android",
      publicKey: REAL_PUBLIC_KEY_B,
    });
    expect(finishResponse.status).toBe(201);
    const phone = await json(finishResponse);
    // The receiver credentials are obtained through the pairing status route.
    const receiverStatus = await SELF.fetch(`https://relay.test/v1/pair/status?sessionId=${start.sessionId}&code=${start.code}`);
    const receiver = await json(receiverStatus);
    const status = await SELF.fetch(
      `https://relay.test/v1/device/status?roomId=${receiver.roomId}&deviceId=${receiver.deviceId}`,
      { headers: { authorization: `Bearer ${receiver.token}` } },
    );
    expect(status.status).toBe(200);
    expect(await json(status)).toMatchObject({
      active: true,
      deviceName: "status-windows-v060",
      deviceType: "windows",
      peerActive: true,
      peerDeviceId: phone.deviceId,
    });

    const revoked = await post(
      "/v1/device/revoke",
      { roomId: receiver.roomId, deviceId: receiver.deviceId },
      receiver.token,
    );
    expect(revoked.status).toBe(200);
    expect((await json(revoked)).revoked).toBe(true);
    const retry = await SELF.fetch(
      `https://relay.test/v1/device/status?roomId=${receiver.roomId}&deviceId=${receiver.deviceId}`,
      { headers: { authorization: `Bearer ${receiver.token}` } },
    );
    expect(retry.status).toBe(401);
    const peerRetry = await SELF.fetch(
      `https://relay.test/v1/device/status?roomId=${phone.roomId}&deviceId=${phone.deviceId}`,
      { headers: { authorization: `Bearer ${phone.token}` } },
    );
    expect(peerRetry.status).toBe(401);
    const peerMessages = await SELF.fetch(
      `https://relay.test/v1/messages?roomId=${phone.roomId}&deviceId=${phone.deviceId}`,
      { headers: { authorization: `Bearer ${phone.token}` } },
    );
    expect(peerMessages.status).toBe(401);
  });

  it("limits new backup registrations per IP without limiting updates", async () => {
    const ip = "198.51.100.60";
    for (let index = 0; index < 10; index += 1) {
      const response = await put(
        "/v1/device/backup",
        { backupId: `backup_rate_v060_${index}`, schema: 1, revision: 1, salt: b64(index + 60, 16), nonce: b64(index + 1, 12), ciphertext: b64(index + 11, 16) },
        `backup-rate-token-v060-${index}-aaaaaaaaaaaaaaaa`,
        ip,
      );
      expect(response.status).toBe(201);
    }
    const limited = await put(
      "/v1/device/backup",
      { backupId: "backup_rate_v060_limited", schema: 1, revision: 1, salt: b64(110, 16), nonce: b64(50, 12), ciphertext: b64(51, 16) },
      "backup-rate-token-v060-limited-aaaaaaaaaaaa",
      ip,
    );
    expect(limited.status).toBe(429);
  });

  it("pairs an Android cloud receiver, confirms it, and reuses message/ACK logic", async () => {
    const startResponse = await post("/v1/pair/start", {
      deviceName: "test-android-receiver",
      deviceType: "android_receiver",
      publicKey: REAL_PUBLIC_KEY_A,
    });
    expect(startResponse.status).toBe(201);
    const start = await json(startResponse);

    const finishResponse = await post("/v1/pair/finish", {
      code: start.code,
      deviceName: "test-sender-phone",
      deviceType: "android",
      publicKey: REAL_PUBLIC_KEY_B,
    });
    expect(finishResponse.status).toBe(201);
    const phone = await json(finishResponse);
    expect(phone.peerDeviceId).toMatch(/^android_receiver_/);
    expect(phone.peerPublicKey).toBe(REAL_PUBLIC_KEY_A);

    const statusResponse = await SELF.fetch(`https://relay.test/v1/pair/status?sessionId=${start.sessionId}&code=${start.code}`);
    expect(statusResponse.status).toBe(200);
    const receiver = await json(statusResponse);
    expect(receiver.deviceId).toBe(phone.peerDeviceId);
    expect(receiver.peerDeviceId).toBe(phone.deviceId);
    expect(receiver.peerPublicKey).toBe(REAL_PUBLIC_KEY_B);
    expect(receiver.peerName).toBe("test-sender-phone");

    const senderConfirm = await post("/v1/pair/confirm", { sessionId: start.sessionId }, phone.token);
    expect(senderConfirm.status).toBe(403);
    const confirm = await post("/v1/pair/confirm", { sessionId: start.sessionId }, receiver.token);
    expect(confirm.status).toBe(200);
    expect((await json(confirm)).confirmed).toBe(true);

    const message = {
      roomId: phone.roomId,
      senderDeviceId: phone.deviceId,
      targetDeviceId: receiver.deviceId,
      id: "android-receiver-message-1",
      createdAt: Date.now(),
      nonce: b64(6, 12),
      ciphertext: b64(7, 16),
    };
    const stored = await post("/v1/messages", message, phone.token);
    expect(stored.status).toBe(201);
    const received = await SELF.fetch(
      `https://relay.test/v1/messages?roomId=${phone.roomId}&deviceId=${receiver.deviceId}`,
      { headers: { authorization: `Bearer ${receiver.token}` } },
    );
    expect(received.status).toBe(200);
    expect((await json(received)).messages).toEqual([{
      roomId: phone.roomId,
      senderDeviceId: phone.deviceId,
      targetDeviceId: receiver.deviceId,
      id: message.id,
      createdAt: message.createdAt,
      nonce: message.nonce,
      ciphertext: message.ciphertext,
    }]);

    const ack = await post(
      "/v1/ack",
      { roomId: phone.roomId, deviceId: receiver.deviceId, messageIds: [message.id] },
      receiver.token,
    );
    expect(ack.status).toBe(200);
    const empty = await SELF.fetch(
      `https://relay.test/v1/messages?roomId=${phone.roomId}&deviceId=${receiver.deviceId}`,
      { headers: { authorization: `Bearer ${receiver.token}` } },
    );
    expect((await json(empty)).messages).toHaveLength(0);
  });

  it("rejects invalid credentials and returns CORS headers", async () => {
    const health = await SELF.fetch("https://relay.test/health");
    expect(health.status).toBe(200);
    const browser = await SELF.fetch("https://relay.test/health", { headers: { origin: "https://client.test" } });
    expect(browser.status).toBe(403);
    expect(browser.headers.get("access-control-allow-origin")).toBeNull();
    const unauthorized = await SELF.fetch("https://relay.test/v1/messages?roomId=x&deviceId=y");
    expect(unauthorized.status).toBe(401);
    const invalidPoint = await post(
      "/v1/pair/start",
      { deviceName: "bad-point", deviceType: "windows", publicKey: invalidP256Key() },
      undefined,
      "203.0.113.21",
    );
    expect(invalidPoint.status).toBe(400);
  });

  it("rate limits anonymous pairing-code guesses by CF-Connecting-IP", async () => {
    const ip = "198.51.100.77";
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await post(
        "/v1/pair/finish",
        { code: "000000", deviceName: "guess", deviceType: "android", publicKey: validP256Key() },
        undefined,
        ip,
      );
      expect(response.status).toBe(401);
    }
    const locked = await post(
      "/v1/pair/finish",
      { code: "000000", deviceName: "guess", deviceType: "android", publicKey: validP256Key() },
      undefined,
      ip,
    );
    expect(locked.status).toBe(429);
  });
});
