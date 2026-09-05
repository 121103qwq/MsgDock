import { SELF, env } from "cloudflare:test";
import type { Env } from "../src/index";
import { describe, expect, it } from "vitest";

type Json = Record<string, any>;

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  return SELF.fetch(`https://msgdock.test${path}`, init);
}

async function post(path: string, body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return request(path, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

async function json(response: Response): Promise<Json> {
  return response.json() as Promise<Json>;
}

function auth(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}

function cookie(response: Response): string {
  const value = response.headers.get("set-cookie") ?? "";
  return value.split(";", 1)[0];
}

describe("MsgDock account API", () => {
  it("keeps users isolated, deduplicates messages, and separates token uses", async () => {
    const aliceRegister = await post(
      "/api/v1/auth/register",
      { username: "alice-account", email: "alice@example.test", password: "correct horse battery" },
      { "x-msgdock-client": "native", "CF-Connecting-IP": "198.51.100.10" },
    );
    expect(aliceRegister.status).toBe(200);
    const alice = await json(aliceRegister);
    expect(alice.session_token).toEqual(expect.any(String));
    expect(alice.user).toMatchObject({ username: "alice-account", email: "alice@example.test" });
    expect(alice.user.password_hash).toBeUndefined();
    const stored = await (env as Env).DB.prepare("SELECT password_hash FROM users WHERE id = ?")
      .bind(alice.user.id).first<{ password_hash: string }>();
    expect(stored?.password_hash).toMatch(/^pbkdf2-sha256-hmac-v1\$100000\$/);

    const bobRegister = await post(
      "/api/v1/auth/register",
      { username: "bob-account", email: "bob@example.test", password: "correct horse battery" },
      { "x-msgdock-client": "native", "CF-Connecting-IP": "198.51.100.11" },
    );
    expect(bobRegister.status).toBe(200);
    const bob = await json(bobRegister);

    const aliceDeviceResponse = await post(
      "/api/v1/devices",
      { name: "Alice Android", type: "android" },
      auth(alice.session_token),
    );
    expect(aliceDeviceResponse.status).toBe(201);
    const aliceDevice = await json(aliceDeviceResponse);
    expect(aliceDevice.device).toMatchObject({ name: "Alice Android", type: "android" });
    expect(aliceDevice.device_token).toEqual(expect.any(String));

    const bobDeviceResponse = await post(
      "/api/v1/devices",
      { name: "Bob Windows", type: "windows" },
      auth(bob.session_token),
    );
    const bobDevice = await json(bobDeviceResponse);

    const sessionCannotPost = await post(
      "/api/v1/messages",
      { client_message_id: "session-not-device", sender: "10086", body: "ignored", received_at: Date.now() },
      auth(alice.session_token),
    );
    expect(sessionCannotPost.status).toBe(401);

    const message = {
      client_message_id: "alice-message-1",
      sender: "10086",
      body: "您的验证码为 583921",
      received_at: 1787400001000,
    };
    const created = await post("/api/v1/messages", message, auth(aliceDevice.device_token));
    expect(created.status).toBe(201);
    expect(await json(created)).toMatchObject({ deduplicated: false, seq: expect.any(Number) });

    const duplicate = await post("/api/v1/messages", message, auth(aliceDevice.device_token));
    expect(duplicate.status).toBe(200);
    expect(await json(duplicate)).toMatchObject({ deduplicated: true, seq: expect.any(Number) });

    const deviceCannotManage = await request("/api/v1/devices", { headers: auth(aliceDevice.device_token) });
    expect(deviceCannotManage.status).toBe(401);

    const aliceMessages = await request("/api/v1/messages?after=0&limit=100", { headers: auth(alice.session_token) });
    expect(aliceMessages.status).toBe(200);
    const aliceMessageBody = await json(aliceMessages);
    expect(aliceMessageBody.messages).toHaveLength(1);
    expect(aliceMessageBody.messages[0]).toMatchObject({
      client_message_id: message.client_message_id,
      sender: message.sender,
      body: message.body,
      source_device: { id: aliceDevice.device.id, name: "Alice Android", type: "android" },
    });
    expect(aliceMessageBody.next_seq).toBe(aliceMessageBody.messages[0].seq);

    const bobMessages = await request("/api/v1/messages?after=0", { headers: auth(bob.session_token) });
    expect(bobMessages.status).toBe(200);
    expect((await json(bobMessages)).messages).toHaveLength(0);

    const aliceCannotUseBobDevice = await post(
      "/api/v1/messages",
      { ...message, client_message_id: "bob-device-attempt" },
      auth(bobDevice.device_token),
    );
    expect(aliceCannotUseBobDevice.status).toBe(201);
    const bobOwnMessages = await request("/api/v1/messages?after=0", { headers: auth(bobDevice.device_token) });
    expect((await json(bobOwnMessages)).messages).toHaveLength(1);
  });

  it("uses an HttpOnly cookie for browser sessions and returns native sessions only with the native header", async () => {
    const browserRegister = await post(
      "/api/v1/auth/register",
      { username: "browser-account", email: "browser@example.test", password: "browser password 123" },
      { "CF-Connecting-IP": "198.51.100.12" },
    );
    expect(browserRegister.status).toBe(200);
    const browserBody = await json(browserRegister);
    expect(browserBody.session_token).toBeUndefined();
    const browserCookie = cookie(browserRegister);
    expect(browserCookie).toMatch(/^msgdock_session=[A-Za-z0-9_-]{40,64}$/);
    expect(browserRegister.headers.get("set-cookie")).toContain("HttpOnly");
    expect(browserRegister.headers.get("set-cookie")).toContain("Secure");
    expect(browserRegister.headers.get("set-cookie")).toContain("SameSite=Lax");

    const devices = await request("/api/v1/devices", { headers: { cookie: browserCookie } });
    expect(devices.status).toBe(200);

    const nativeLogin = await post(
      "/api/v1/auth/login",
      { identifier: "browser@example.test", password: "browser password 123" },
      { "x-msgdock-client": "native", "CF-Connecting-IP": "198.51.100.13" },
    );
    expect(nativeLogin.status).toBe(200);
    const nativeBody = await json(nativeLogin);
    expect(nativeBody.session_token).toEqual(expect.any(String));
    expect(nativeLogin.headers.get("set-cookie")).toBeNull();
    const wrongPassword = await post("/api/v1/auth/login",
      { identifier: "browser@example.test", password: "wrong password 123" },
      { "CF-Connecting-IP": "198.51.100.13" });
    expect(wrongPassword.status).toBe(401);

    const loggedOut = await post("/api/v1/auth/logout", undefined, { cookie: browserCookie });
    expect(loggedOut.status).toBe(200);
    const afterLogout = await request("/api/v1/me", { headers: { cookie: browserCookie } });
    expect(afterLogout.status).toBe(401);
  });

  it("supports cursor pagination beyond the 100-message page size", async () => {
    const register = await post(
      "/api/v1/auth/register",
      { username: "pagination-account", email: "pagination@example.test", password: "pagination password 123" },
      { "x-msgdock-client": "native", "CF-Connecting-IP": "198.51.100.40" },
    );
    expect(register.status).toBe(200);
    const account = await json(register);

    const deviceResponse = await post(
      "/api/v1/devices",
      { name: "Pagination Android", type: "android" },
      auth(account.session_token),
    );
    expect(deviceResponse.status).toBe(201);
    const device = await json(deviceResponse);

    for (let index = 0; index < 205; index += 1) {
      const created = await post(
        "/api/v1/messages",
        {
          client_message_id: `pagination-${index}`,
          sender: "10086",
          body: `message-${index}`,
          received_at: 1787400010000 + index,
        },
        auth(device.device_token),
      );
      expect(created.status).toBe(201);
    }

    const recent = await request("/api/v1/messages?limit=100", { headers: auth(account.session_token) });
    expect(recent.status).toBe(200);
    const recentBody = await json(recent);
    expect(recentBody.messages).toHaveLength(100);
    expect(recentBody.messages[0].body).toBe("message-105");
    expect(recentBody.messages[99].body).toBe("message-204");

    let cursor = 0;
    let pageCount = 0;
    let messageCount = 0;
    let firstBody = "";
    let lastBody = "";
    while (true) {
      const page = await request(`/api/v1/messages?after=${cursor}&limit=100`, { headers: auth(account.session_token) });
      expect(page.status).toBe(200);
      const pageBody = await json(page);
      const messages = pageBody.messages as Array<{ body: string }>;
      if (pageCount === 0) firstBody = messages[0].body;
      if (messages.length > 0) lastBody = messages[messages.length - 1].body;
      pageCount += 1;
      messageCount += messages.length;
      const next = Number(pageBody.next_seq);
      if (messages.length < 100) break;
      expect(next).toBeGreaterThan(cursor);
      cursor = next;
    }
    expect(pageCount).toBe(3);
    expect(messageCount).toBe(205);
    expect(firstBody).toBe("message-0");
    expect(lastBody).toBe("message-204");
  });
});
