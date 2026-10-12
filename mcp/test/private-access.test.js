import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:net";
import { randomBytes } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { test } from "node:test";

const key = `Aa_${randomBytes(32).toString("hex")}`;
const token = `backend_${randomBytes(32).toString("hex")}`;
const initialize = { jsonrpc: "2.0", id: 1, method: "initialize", params: {
  protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "private-access-test", version: "1.0" }
} };

async function launch(t, privateKey) {
  const probe = createServer();
  probe.listen(0, "127.0.0.1");
  await once(probe, "listening");
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath, ["server.js"], {
    cwd: new URL("../", import.meta.url),
    env: { ...process.env, PORT: String(port), MCP_PRIVATE_KEY: privateKey,
      LINJIAN_URL: "http://127.0.0.1:1", LINJIAN_TOKEN: token },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  child.stdout.on("data", chunk => { output += chunk; });
  child.stderr.on("data", chunk => { output += chunk; });
  const exited = once(child, "exit");
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill();
    await exited;
  });
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 100; i++) {
    if (child.exitCode !== null) return { child, output, base };
    try {
      if ((await fetch(`${base}/health`)).ok) return { child, output, base };
    } catch {}
    await delay(25);
  }
  throw new Error("MCP process did not start or exit in time");
}

function rpc(base, route, body = initialize) {
  return fetch(base + route, { method: "POST", headers: {
    "Content-Type": "application/json", Accept: "application/json, text/event-stream"
  }, body: JSON.stringify(body), signal: AbortSignal.timeout(5000) });
}

test("missing, invalid, and reused backend keys fail closed", { timeout: 20000 }, async t => {
  for (const value of ["", "a".repeat(47), "a".repeat(129), "a".repeat(48) + "/", token]) {
    const { child, output } = await launch(t, value);
    assert.notEqual(child.exitCode, null);
    assert.notEqual(child.exitCode, 0);
    assert.match(output, /MCP_PRIVATE_KEY/);
    assert.doesNotMatch(output, /listening on/);
  }
});

test("private MCP routes preserve transports and deny unauthenticated access", { timeout: 20000 }, async t => {
  const { base, output } = await launch(t, key);
  assert.ok(!output.includes(key));
  for (const route of ["/mcp", "/mcp-wallet", "/sse", "/messages"]) {
    for (const prefix of ["", "/private/wrong", `/private/${key.toLowerCase()}`]) {
      for (const method of ["GET", "POST"]) {
        const res = await fetch(base + prefix + route, { method });
        assert.equal(res.status, 404, `${method} ${prefix === "" ? "legacy" : "invalid-key"} ${route}`);
      }
    }
  }
  for (const route of ["/", "/health"]) {
    const res = await fetch(base + route);
    assert.equal(res.status, 200);
    const body = await res.text();
    for (const secret of [key, token, "127.0.0.1:1", "configured_linjian_url", "effective_linjian_url", "fallback_linjian_urls"]) {
      assert.ok(!body.includes(secret), `${route} must not expose ${secret === key ? "private key" : secret}`);
    }
  }
  const prefix = `/private/${key}`;
  for (const route of ["/mcp", "/mcp-wallet"]) {
    assert.equal((await fetch(base + prefix + route)).status, 405);
    const res = await rpc(base, prefix + route);
    assert.equal(res.status, 200);
    assert.match(await res.text(), /"protocolVersion"/);
    const list = await rpc(base, prefix + route, { jsonrpc: "2.0", id: 2, method: "tools/list", params: {} });
    assert.equal(list.status, 200);
    const listed = await list.text();
    assert.match(listed, /"tools"/);
    if (route === "/mcp") {
      for (const name of ["get_study_room_messages", "get_study_timer_status", "start_study_timer", "pause_study_timer", "resume_study_timer", "stop_study_timer"])
        assert.ok(listed.includes('"' + name + '"'), `missing study tool: ${name}`);
    }
  }
  assert.equal((await rpc(base, prefix + "/messages?sessionId=unknown")).status, 404);
  const abort = new AbortController();
  const sse = await fetch(base + prefix + "/sse", { signal: abort.signal });
  try {
    assert.equal(sse.status, 200);
    const reader = sse.body.getReader();
    const first = await reader.read();
    const event = new TextDecoder().decode(first.value);
    assert.match(event, /event: endpoint/);
    const endpoint = event.match(/data: ([^\r\n]+)/)?.[1];
    assert.ok(endpoint.startsWith(prefix + "/messages?sessionId="));
    assert.equal((await rpc(base, endpoint)).status, 202);
    const result = new TextDecoder().decode((await reader.read()).value);
    assert.match(result, /"protocolVersion"/);
  } finally { abort.abort(); }
});
