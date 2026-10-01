'use strict';
// PR1 device smoke tests. All UI operations use the independent android-test MCP.
// ADB is limited to emulator identity, configuration, app data and diagnostics.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createRequire} = require('node:module');
const {execFileSync} = require('node:child_process');
const {randomUUID} = require('node:crypto');
const root = path.resolve(__dirname, '..');
const toolRoot = path.join(process.env.USERPROFILE, '.codex', 'tools', 'android-test');
const sdk = createRequire(path.join(toolRoot, 'package.json'));
const {Client} = sdk('@modelcontextprotocol/sdk/client/index.js');
const {StdioClientTransport} = sdk('@modelcontextprotocol/sdk/client/stdio.js');
const device = 'emulator-5680', pkg = 'com.xgy.lansms';
const apk = path.resolve(process.argv[2] || '');
assert(process.argv[2] && fs.existsSync(apk), 'Pass the PR1 APK path');
const out = path.join(root, 'build', 'android-pr1-' + new Date().toISOString().replace(/[:.]/g, '-'));
fs.mkdirSync(out, {recursive: true});
const report = {device, apk, passed: false, checks: [], screenshots: []};
const client = new Client({name: 'msgdock-pr1-qa', version: '1.0.0'});
const transport = new StdioClientTransport({command: process.execPath,
  args: [path.join(toolRoot, 'start-mcp.cjs')], cwd: toolRoot, stderr: 'pipe'});
transport.stderr.on('data', () => {});
const adb = (...args) => execFileSync('C:\\Android\\Sdk\\platform-tools\\adb.exe',
  ['-P', '5038', '-s', device, ...args],
  {encoding: 'utf8', timeout: 20000, windowsHide: true}).trim();
const wait = ms => new Promise(r => setTimeout(r, ms));
const text = r => (r.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n');
async function call(name, args = {}) {
  const r = await client.callTool({name, arguments: args}, undefined, {timeout: 45000});
  assert(!r.isError && !/^Error:/m.test(text(r)), name + ': ' + text(r));
  return r;
}
let step = 0, originalFont, originalDensity, forwardPort, changedDisplay = false;
async function tree() {
  const raw = text(await call('mobile_list_elements_on_screen', {device}));
  fs.writeFileSync(path.join(out, ++step + '-ui.txt'), raw);
  return JSON.parse(raw.slice(raw.indexOf('[')));
}
const id = (xs, name) => xs.find(e => e.identifier === pkg + ':id/' + name);
const bottom = e => e.coordinates.y + e.coordinates.height;
async function shot(name) {
  const r = await call('mobile_take_screenshot', {device});
  const img = r.content.find(c => c.type === 'image');
  assert(img);
  const file = path.join(out, name + '.png');
  fs.writeFileSync(file, Buffer.from(img.data, 'base64'));
  report.screenshots.push(file);
}
async function tap(e) {
  assert(e?.coordinates, 'Missing current target');
  const c = e.coordinates;
  await call('mobile_click_on_screen_at_coordinates',
    {device, x: Math.round(c.x + c.width / 2), y: Math.round(c.y + c.height / 2)});
}
async function reveal(name, direction = 'up') {
  for (let n = 0; n < 18; n++) {
    const xs = await tree(), e = id(xs, name);
    if (e && e.coordinates.height > 20) return e;
    const s = id(xs, 'main_scroll');
    assert(s, 'Main scroll region missing');
    const c = s.coordinates;
    await call('mobile_swipe_on_screen', {device, direction,
      x: Math.round(c.x + c.width / 2),
      y: Math.round(c.y + c.height * (direction === 'up' ? 0.82 : 0.18)),
      distance: Math.round(c.height * 0.62)});
  }
  throw new Error('Could not reach ' + name);
}
async function orientation(landscape) {
  await call('mobile_set_orientation', {device, orientation: landscape ? 'landscape' : 'portrait'});
  for (let n = 0; n < 12; n++) {
    const xs = await tree(), c = xs.find(e => e.identifier === 'android:id/content')?.coordinates;
    if (c && (c.width > c.height) === landscape) return;
    await wait(500);
  }
  throw new Error('Actual orientation did not change');
}
const pass = s => report.checks.push(s);
(async () => {
  try {
    await client.connect(transport, {timeout: 30000});
    const devices = JSON.parse(text(await call('mobile_list_available_devices'))).devices;
    assert(devices.some(d => d.id === device));
    assert(adb('emu', 'avd', 'name').includes('MsgDock_Codex_API34'));
    assert.equal(adb('shell', 'getprop', 'sys.boot_completed'), '1');
    assert.equal(adb('shell', 'getprop', 'ro.kernel.qemu'), '1');
    originalFont = adb('shell', 'settings', 'get', 'system', 'font_scale');
    originalDensity = adb('shell', 'wm', 'density').match(/Override density: (\d+)/)?.[1];
    const historyBefore = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
    await call('mobile_install_app', {device, path: apk});
    assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionName=0.7.7'));
    assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), historyBefore);
    await call('mobile_launch_app', {device, packageName: pkg});
    await wait(800);
    let xs = await tree(), s = id(xs, 'main_scroll');
    const status = xs.find(e => e.identifier === 'android:id/statusBarBackground');
    const nav = xs.find(e => e.identifier === 'android:id/navigationBarBackground');
    assert(s && status && nav && id(xs, 'text_status_title'));
    assert(s.coordinates.y >= bottom(status) && bottom(s) <= nav.coordinates.y);
    assert(!id(xs, 'edit_account_password'));
    await shot('home-status-first');
    pass('Version 0.7.7 installed without clearing history; status-first home respects system bars, account form initially collapsed');

    await tap(await reveal('btn_toggle_account'));
    await tap(await reveal('edit_account_password'));
    await wait(700);
    xs = await tree();
    const input = id(xs, 'edit_account_password'), keyboardScroll = id(xs, 'main_scroll');
    assert(input?.focused && keyboardScroll.coordinates.height < s.coordinates.height);
    assert(bottom(input) <= bottom(keyboardScroll));
    await shot('account-keyboard');
    await call('mobile_press_button', {device, button: 'BACK'});
    await wait(400);
    assert.equal(id(await tree(), 'main_scroll').coordinates.height, s.coordinates.height);
    pass('Expanded account form remains reachable above keyboard; dismissing keyboard restores the viewport');

    // The PR specifically requests 360 dp / 200% text. Only our own emulator is changed.
    changedDisplay = true;
    adb('shell', 'wm', 'density', '480');
    adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0');
    await wait(700);
    await reveal('btn_account_login');
    await shot('360dp-200pct-account');
    await orientation(true);
    await reveal('btn_account_login');
    await shot('landscape-200pct-account');
    pass('Account expansion survives rotation; login remains reachable at 360 dp with 200% text and in landscape');

    await orientation(false);
    adb('shell', 'settings', 'put', 'system', 'font_scale', originalFont === 'null' ? '1.0' : originalFont);
    adb('shell', 'wm', 'density', originalDensity || 'reset');
    changedDisplay = false;
    await wait(500);
    await tap(await reveal('btn_toggle_advanced'));
    await tap(await reveal('btn_background_guide'));
    xs = await tree();
    assert(xs.some(e => e.text === '后台运行教程') && xs.some(e => e.text === '返回'));
    await shot('background-guide');
    await tap(xs.find(e => e.text === '返回'));
    pass('Advanced settings expand and the offline background guide opens and returns');

    await tap(await reveal('btn_start_receiver'));
    xs = await tree();
    const allow = xs.find(e => /^(Allow|允许)$/i.test(e.text || ''));
    if (allow) await tap(allow);
    await wait(1000);
    await reveal('text_receiver_status');
    xs = await tree();
    assert(xs.some(e => String(e.text || '').includes('LAN 已就绪')));
    forwardPort = adb('forward', 'tcp:0', 'tcp:58123');
    assert(/^\d+$/.test(forwardPort));
    const address = 'http://127.0.0.1:' + forwardPort;
    const page = await (await fetch(address, {signal: AbortSignal.timeout(5000)})).text();
    const key = page.match(/>(\d{6})<\/b>/)?.[1];
    assert(key);
    const message = {id: randomUUID(), from: 'Codex PR1 QA', text: '【MsgDock QA】验证码 583921，仅用于独立模拟器验收。',
      device: 'Codex independent emulator', receivedAt: Date.now(), sim: -1};
    report.syntheticMessageId = message.id;
    for (let n = 0; n < 2; n++) {
      const r = await fetch(address + '/sms', {method: 'POST',
        headers: {'Content-Type': 'application/json', 'X-Xgy-Key': key},
        body: JSON.stringify(message), signal: AbortSignal.timeout(5000)});
      assert.equal(r.status, 200);
    }
    const history = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
    assert.equal(history.split('\n').filter(line => {try {return JSON.parse(line).id === message.id;} catch {return false;}}).length, 1);
    await tap(await reveal('btn_stop_receiver'));
    await reveal('container_recent', 'down');
    await wait(3200);
    xs = await tree();
    assert(xs.some(e => String(e.text || '').includes('Codex PR1 QA')));
    assert(xs.some(e => e.identifier === pkg + ':id/text_recent_code' && e.text === '583921'));
    await shot('recent-lan-code');
    await tap(await reveal('btn_account_inbox', 'down'));
    xs = await tree();
    assert(xs.some(e => String(e.text || '').includes('583921')));
    await shot('all-history');
    await call('mobile_press_button', {device, button: 'BACK'});
    pass('Actual local HTTP delivery persists before success, deduplicates retries, and renders OTP in recent and full history');
    const notification = adb('shell', 'dumpsys', 'notification', '--noredact');
    fs.writeFileSync(path.join(out, 'notifications.txt'), notification);
    assert(notification.includes('复制验证码'));
    pass('System notification contains the new copy-code action (clipboard click not claimed)');
    report.passed = true;
  } catch (e) {
    report.error = e.stack;
    process.exitCode = 1;
    try {await shot('failure');} catch {}
  } finally {
    if (changedDisplay) {
      try {
        await orientation(false);
        adb('shell', 'settings', 'put', 'system', 'font_scale', originalFont === 'null' ? '1.0' : originalFont);
        adb('shell', 'wm', 'density', originalDensity || 'reset');
      } catch (e) {report.restoreError = e.stack; report.passed = false; process.exitCode = 1;}
    }
    if (forwardPort) {try {adb('forward', '--remove', 'tcp:' + forwardPort);} catch {}}
    try {fs.writeFileSync(path.join(out, 'crash-log.txt'), adb('shell', 'logcat', '-b', 'crash', '-d'));} catch {}
    await client.close().catch(() => {});
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({passed: report.passed, checks: report.checks, error: report.error, out}, null, 2));
  }
})();
