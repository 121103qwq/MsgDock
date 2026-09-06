'use strict';

// Device-level regression for permission callbacks and receiver status. Uses the
// already installed MCP SDK; no project dependency or production test hook.
// Only the disposable Codex emulator is supported. Never target a personal phone.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { execFileSync } = require('node:child_process');
const { randomUUID } = require('node:crypto');
const root = path.resolve(__dirname, '..');
const toolRoot = path.join(process.env.USERPROFILE, '.codex', 'tools', 'android-test');
const sdkRequire = createRequire(path.join(toolRoot, 'package.json'));
const { Client } = sdkRequire('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = sdkRequire('@modelcontextprotocol/sdk/client/stdio.js');
const serial = 'emulator-5680';
const pkg = 'com.xgy.lansms';
const apk = path.resolve(process.argv[2] || '');
assert(process.argv[2] && fs.existsSync(apk), 'Usage: node tools/test-android-ui-mcp.cjs <apk> [permissions|lifecycle|inbox|guide]');
const scenario = process.argv[3] || 'permissions';
assert(['permissions', 'lifecycle', 'inbox', 'guide'].includes(scenario));
const output = path.join(root, 'build', 'android-ui-' + new Date().toISOString().replace(/[:.]/g, '-'));
fs.mkdirSync(output, { recursive: true });
const report = { device: serial, apk, scenario, checks: [], screenshots: [], passed: false };
const client = new Client({ name: 'msgdock-permission-qa', version: '1.0.0' });
const transport = new StdioClientTransport({
    command: process.execPath, args: [path.join(toolRoot, 'start-mcp.cjs')], cwd: toolRoot, stderr: 'pipe',
});
transport.stderr.on('data', data => {
    // Mobile MCP also checks iOS on Windows; omit only that unrelated warning.
    const text = data.toString().replace(/^go-ios is not installed, no physical iOS devices can be detected\r?\n/gm, '');
    if (text) process.stderr.write(text);
});
const adb = (...args) => execFileSync('C:\\Android\\Sdk\\platform-tools\\adb.exe', ['-P', '5038', '-s', serial, ...args], {
    encoding: 'utf8', timeout: 20000, windowsHide: true,
    env: { ...process.env, ADB_SERVER_SOCKET: 'tcp:127.0.0.1:5038' },
}).trim();
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const textOf = result => (result.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n');
let step = 0;
let forwardPort;
let ownsReverse = false;
let rotated = false;
let inboxFixtureBackup;
let originalFontScale;
// Fixture writes are restricted to two app-private files on the disposable emulator.
function writeFixture(name, content) {
    assert(['files/cloud-inbox.jsonl', 'shared_prefs/msgdock_account.xml'].includes(name));
    execFileSync('C:\\Android\\Sdk\\platform-tools\\adb.exe',
        ['-P', '5038', '-s', serial, 'shell', 'run-as', pkg, 'sh', '-c', `"cat > ${name}"`],
        { input: content, timeout: 20000, windowsHide: true });
}
async function call(name, args = {}) {
    const result = await client.callTool({ name, arguments: args }, undefined, { timeout: 90000 });
    assert(!result.isError && !/^Error:|Please fix the issue and try again\./m.test(textOf(result)), name + ': ' + textOf(result));
    return result;
}
async function tree() {
    const raw = textOf(await call('mobile_list_elements_on_screen', { device: serial }));
    fs.writeFileSync(path.join(output, `${++step}-ui.txt`), raw);
    return JSON.parse(raw.slice(raw.indexOf('[')));
}
const contains = (elements, value) => elements.some(e => String(e.text || e.label || '').includes(value));
async function reveal(value, direction = 'up') {
    for (let i = 0; i < 12; i++) {
        const elements = await tree();
        const target = elements.find(e => e.text === value);
        if (target) return target;
        const region = elements.filter(e => /ScrollView|ListView/.test(e.type || '') && e.coordinates)
            .sort((a, b) => b.coordinates.height - a.coordinates.height)[0];
        if (region) {
            const { x, y, width, height } = region.coordinates;
            await call('mobile_swipe_on_screen', { device: serial, direction,
                x: Math.round(x + width / 2), y: Math.round(y + height * (direction === 'up' ? 0.85 : 0.15)),
                distance: Math.round(height * 0.65) });
        } else {
            await call('mobile_swipe_on_screen', { device: serial, direction });
        }
    }
    throw new Error('Not found after scrolling: ' + value);
}
async function tap(element) {
    assert(element && element.coordinates, 'Control missing from the current UI tree');
    const { x, y, width, height } = element.coordinates;
    await call('mobile_click_on_screen_at_coordinates', { device: serial, x: Math.round(x + width / 2), y: Math.round(y + height / 2) });
}
async function tapText(value, direction) { await tap(await reveal(value, direction)); }
async function permission(allow) {
    const elements = await tree();
    const pattern = allow ? /^(Allow|允许)$/i : /^(Don.t allow|不允许|拒绝)$/i;
    await tap(elements.find(e => pattern.test(e.text || '')));
}
async function screenshot(label) {
    const result = await call('mobile_take_screenshot', { device: serial });
    const shot = result.content.find(c => c.type === 'image');
    assert(shot, 'Screenshot missing');
    const file = path.join(output, label + '.png');
    fs.writeFileSync(file, Buffer.from(shot.data, 'base64'));
    report.screenshots.push(file);
}
function passed(check) {
    report.checks.push(check);
    console.log('PASS: ' + check);
}
async function waitForText(value) {
    for (let i = 0; i < 5; i++) {
        const elements = await tree();
        if (contains(elements, value)) return elements;
        await delay(1000);
    }
    throw new Error('Expected live state missing: ' + value);
}

async function guide() {
    adb('shell', 'am', 'force-stop', pkg);
    report.previousVersion = adb('shell', 'dumpsys', 'package', pkg).match(/versionName=(\S+)/)?.[1];
    const history = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
    const oldBatteryState = adb('shell', 'dumpsys', 'deviceidle', 'whitelist');
    await call('mobile_install_app', { device: serial, path: apk });
    const expected = fs.readFileSync(path.join(root, 'app', 'build.gradle'), 'utf8').match(/versionName '([^']+)'/)[1];
    assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionName=' + expected));
    assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), history);
    await call('mobile_launch_app', { device: serial, packageName: pkg });
    await tapText('后台运行教程 · 电池 / 小锁 / 自启动', 'down');
    let elements = await waitForText('后台运行教程');
    assert(contains(elements, '品牌：Google / 原生 Android'));
    assert(contains(elements, '系统电池优化：'));
    await screenshot('guide-default');
    passed('Upgrade preserves saved inbox; home opens offline guide with detected brand and honest battery status');

    const examples = [
        ['小米 / Redmi / POCO', '省电策略', '长按 MsgDock', '后台自启动'],
        ['华为', '不允许', '向下拉', '手动管理'],
        ['荣耀', '不允许', '下滑并停一下', '应用启动管理'],
        ['OPPO / 一加', '耗电管理', '更多菜单', '自启动管理'],
        ['realme 真我', '应用耗电管理', '向下拉', '隐私权限'],
        ['vivo / iQOO', '后台耗电管理', '向下滑', '应用与权限'],
        ['三星', '从不休眠', 'Keep open', '通常没有'],
        ['华硕 / ROG', '不受限制', '没有统一', 'Auto-start Manager'],
        ['Google / 原生 Android', '应用电池用量', '通常没有', '通常没有'],
        ['其他：魅族 / 中兴 / 努比亚 / 联想等', '后台运行', '有明确的', '隐藏组件'],
    ];
    for (const [brand, battery, recents, autostart] of examples) {
        await tap((await tree()).find(e => String(e.text || '').startsWith('品牌：')));
        await tapText(brand);
        elements = await tree();
        assert(contains(elements, '品牌：' + brand));
        assert(contains(elements, battery), brand + ': battery text');
        // The native page is deliberately scrollable rather than shrinking long instructions.
        let seen = elements.map(e => e.text || '').join('\n');
        for (let i = 0; i < 7 && !(seen.includes(recents) && seen.includes(autostart)); i++) {
            await call('mobile_swipe_on_screen', { device: serial, direction: 'up', distance: 800 });
            elements = await tree();
            seen += '\n' + elements.map(e => e.text || '').join('\n');
        }
        assert(seen.includes(recents), brand + ': recents text');
        assert(seen.includes(autostart), brand + ': autostart text');
        if (['小米 / Redmi / POCO', '三星'].includes(brand)) await screenshot(brand.startsWith('小米') ? 'guide-xiaomi-lock' : 'guide-samsung');
    }
    passed('All 10 brand groups display their own battery, recent-task lock and autostart instructions');

    await tapText('查看本品牌官方参考资料');
    elements = await tree();
    assert(contains(elements, '官方参考资料 · 需联网打开'));
    assert(contains(elements, 'Android 通用：每个应用的电池设置'));
    await tapText('取消');
    await tap((await tree()).find(e => String(e.text || '').startsWith('品牌：')));
    await tapText('华为', 'down');
    originalFontScale = adb('shell', 'settings', 'get', 'system', 'font_scale');
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3');
    await waitForText('品牌：华为');
    await screenshot('guide-large-font');
    rotated = true;
    await call('mobile_set_orientation', { device: serial, orientation: 'landscape' });
    await waitForText('品牌：华为');
    await tapText('查看本品牌官方参考资料');
    assert(contains(await tree(), '华为：应用无法后台运行'));
    await tapText('取消');
    await screenshot('guide-landscape');
    await call('mobile_set_orientation', { device: serial, orientation: 'portrait' });
    rotated = false;
    if (originalFontScale === 'null') adb('shell', 'settings', 'delete', 'system', 'font_scale');
    else adb('shell', 'settings', 'put', 'system', 'font_scale', originalFontScale);
    originalFontScale = undefined;
    passed('Manual brand survives larger text and rotation; long content and correct official-source list remain reachable');

    await tapText('打开 MsgDock 应用信息', 'down');
    await delay(700);
    assert(adb('shell', 'dumpsys', 'activity', 'activities').match(/ResumedActivity[=:].*com\.android\.settings/));
    assert(contains(await tree(), 'MsgDock'));
    await call('mobile_press_button', { device: serial, button: 'BACK' });
    await waitForText('品牌：华为');
    await tapText('系统电池优化列表', 'down');
    await delay(700);
    assert(adb('shell', 'dumpsys', 'activity', 'activities').match(/ResumedActivity[=:].*com\.android\.settings/));
    await screenshot('guide-system-battery');
    await call('mobile_press_button', { device: serial, button: 'BACK' });
    await waitForText('品牌：华为');
    assert.equal(adb('shell', 'dumpsys', 'deviceidle', 'whitelist'), oldBatteryState);
    await tapText('返回');
    assert(contains(await tree(), '本机收件箱 · 短信历史'));
    assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), history);
    passed('App-info and battery-list shortcuts open real system pages and return without changing whitelist or inbox');
}

async function lifecycle() {
    const expected = fs.readFileSync(path.join(root, 'app', 'build.gradle'), 'utf8').match(/versionName '([^']+)'/)[1];
    assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionName=' + expected));
    assert(!adb('reverse', '--list').includes('tcp:58123'), 'An existing port fixture must not be replaced');
    // adbd owns this temporary listener. Removing this exact reverse mapping
    // releases it without starting or killing another app/process.
    adb('reverse', '--no-rebind', 'tcp:58123', 'tcp:9');
    ownsReverse = true;
    await call('mobile_launch_app', { device: serial, packageName: pkg });
    await tapText('启动接收');
    await permission(true);
    const failed = await waitForText('LAN 监听失败');
    assert(!contains(failed, 'LAN 已就绪'));
    await screenshot('listener-failure');
    passed('An occupied LAN port is shown as a listener failure, not running successfully');

    await tapText('停止接收');
    await waitForText('状态：未启动');
    adb('reverse', '--remove', 'tcp:58123');
    ownsReverse = false;
    await tapText('启动接收');
    await waitForText('LAN 已就绪');
    passed('Stopping and starting after releasing the port restores real LAN readiness');

    const savedHistory = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
    assert(savedHistory.includes('Codex isolated test'));
    await call('mobile_terminate_app', { device: serial, packageName: pkg });
    await call('mobile_launch_app', { device: serial, packageName: pkg });
    await reveal('启动接收');
    await waitForText('LAN 已就绪');
    assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), savedHistory);
    await screenshot('process-reopen');
    passed('Explicitly reopening after process termination restores the enabled receiver and retains history');

    await tapText('停止接收');
    await waitForText('状态：未启动');
    adb('shell', 'pm', 'revoke', pkg, 'android.permission.NEARBY_WIFI_DEVICES');
    adb('shell', 'pm', 'clear-permission-flags', pkg, 'android.permission.NEARBY_WIFI_DEVICES', 'user-set', 'user-fixed');
    await call('mobile_launch_app', { device: serial, packageName: pkg });
    await tapText('扫描 LAN');
    rotated = true;
    await call('mobile_set_orientation', { device: serial, orientation: 'landscape' });
    await permission(true);
    await delay(6500);
    const scanned = await tree();
    assert(contains(scanned, '未发现设备') || contains(scanned, '选择接收端'));
    await screenshot('permission-rotation');
    await call('mobile_press_button', { device: serial, button: 'BACK' });
    passed('A permission request survives Activity recreation and resumes discovery once granted');
}

async function inbox() {
    const originalHistory = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
    const hasAccountPrefs = adb('shell', 'run-as', pkg, 'ls', 'shared_prefs').split(/\s+/).includes('msgdock_account.xml');
    const accountPrefs = hasAccountPrefs ? adb('shell', 'run-as', pkg, 'cat', 'shared_prefs/msgdock_account.xml')
        : '<?xml version="1.0" encoding="utf-8"?><map />';
    assert(!/<string name="(?:session_token|device_token|user_id)">[^<]+<\/string>/.test(accountPrefs),
        'Inbox QA requires an emulator without a real account');
    report.previousVersion = adb('shell', 'dumpsys', 'package', pkg).match(/versionName=(\S+)/)?.[1];
    await call('mobile_install_app', { device: serial, path: apk });
    const expected = fs.readFileSync(path.join(root, 'app', 'build.gradle'), 'utf8').match(/versionName '([^']+)'/)[1];
    assert(adb('shell', 'dumpsys', 'package', pkg).includes('versionName=' + expected));
    assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), originalHistory);
    await call('mobile_launch_app', { device: serial, packageName: pkg });
    await tapText('本机收件箱 · 短信历史', 'down');
    await waitForText('Permission regression 246810');
    await screenshot('upgraded-local-history');
    await tapText('关闭');
    passed('Upgrade retains the old inbox and exposes existing LAN history without login');

    await tapText('启动接收');
    const possiblePermission = await tree();
    if (possiblePermission.some(e => /^(Allow|允许)$/i.test(e.text || ''))) await permission(true);
    await waitForText('LAN 已就绪');
    forwardPort = adb('forward', 'tcp:0', 'tcp:58123');
    assert(/^\d+$/.test(forwardPort));
    const address = `http://127.0.0.1:${forwardPort}`;
    const page = await (await fetch(address, { signal: AbortSignal.timeout(5000) })).text();
    const pairingCode = page.match(/>(\d{6})<\/b>/)?.[1];
    assert(pairingCode);
    const message = { id: randomUUID(), from: 'Codex inbox QA', text: 'Inbox copy check 583921',
        receivedAt: Date.now(), sim: -1, device: 'Codex isolated test' };
    for (let i = 0; i < 2; i++) {
        const received = await fetch(address + '/sms', { method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Xgy-Key': pairingCode },
            body: JSON.stringify(message), signal: AbortSignal.timeout(5000) });
        assert.equal(received.status, 200);
    }
    report.syntheticMessageId = message.id;
    await tapText('停止接收');
    const savedHistory = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
    assert.equal(savedHistory.split('\n').filter(line => { try { return JSON.parse(line).id === message.id; } catch { return false; } }).length, 1);
    await tapText('本机收件箱 · 短信历史', 'down');
    let elements = await waitForText(message.text);
    assert.equal(elements.filter(e => String(e.text || '').includes(message.text)).length, 1);
    await tap(elements.find(e => String(e.text || '').includes(message.text)));
    elements = await tree();
    assert(contains(elements, '局域网') && contains(elements, message.text));
    await screenshot('lan-message-detail');
    await tapText('复制全文');
    await tapText('用户名（登录时也可填邮箱）');
    adb('shell', 'input', 'keyevent', '279'); // Paste verifies the actual clipboard contents.
    assert(contains(await tree(), message.text));
    await call('mobile_press_button', { device: serial, button: 'BACK' });
    passed('A real LAN delivery is shown once with source/time and copies its full body');

    await tapText('本机收件箱 · 短信历史', 'down');
    elements = await waitForText(message.text);
    await tap(elements.find(e => String(e.text || '').includes(message.text)));
    await tapText('复制验证码');
    await tapText('邮箱（注册时填写）');
    adb('shell', 'input', 'keyevent', '279');
    assert((await tree()).some(e => e.text === '583921' && e.type === 'android.widget.EditText'));
    await call('mobile_press_button', { device: serial, button: 'BACK' });
    passed('Copy verification code places only the six digits on the clipboard');

    await call('mobile_terminate_app', { device: serial, packageName: pkg });
    await call('mobile_launch_app', { device: serial, packageName: pkg });
    await tapText('本机收件箱 · 短信历史', 'down');
    await waitForText(message.text);
    await screenshot('inbox-process-reopen');
    assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), savedHistory);
    passed('Explicit process reopen preserves the new LAN history');

    // Test stored-account isolation without registering or contacting any real account.
    await call('mobile_terminate_app', { device: serial, packageName: pkg });
    inboxFixtureBackup = { history: savedHistory + '\n', prefs: accountPrefs };
    const fixtures = ['a', 'b'].map(owner => ({ ...message, id: `account:qa-${owner}:1`,
        deliveryId: 'qa-shared-delivery', source: 'account', accountUserId: `qa-${owner}`,
        text: `private account ${owner.toUpperCase()}` }));
    writeFixture('files/cloud-inbox.jsonl', savedHistory + '\n' + fixtures.map(row => JSON.stringify(row)).join('\n') + '\n');
    for (const owner of ['a', 'b', '']) {
        writeFixture('shared_prefs/msgdock_account.xml', `<?xml version="1.0" encoding="utf-8"?><map><string name="user_id">${owner ? 'qa-' + owner : ''}</string></map>`);
        await call('mobile_launch_app', { device: serial, packageName: pkg });
        await tapText('本机收件箱 · 短信历史', 'down');
        elements = await waitForText(message.text);
        assert.equal(contains(elements, 'private account A'), owner === 'a');
        assert.equal(contains(elements, 'private account B'), owner === 'b');
        await screenshot('account-scope-' + (owner || 'logged-out'));
        await call('mobile_terminate_app', { device: serial, packageName: pkg });
    }
    passed('On-device account fixtures show only the selected account and hide both after logout');
}

(async () => {
    try {
        await client.connect(transport, { timeout: 30000 });
        const { devices } = JSON.parse(textOf(await call('mobile_list_available_devices')));
        assert.equal(devices.length, 1);
        assert.equal(devices[0].id, serial);
        assert.equal(devices[0].type, 'emulator');
        assert(adb('emu', 'avd', 'name').includes('MsgDock_Codex_API34'));
        assert.equal(adb('shell', 'getprop', 'sys.boot_completed'), '1');
        report.server = client.getServerVersion();
        if (scenario === 'guide') {
            await guide();
            report.passed = true;
            return;
        }
        if (scenario === 'inbox') {
            await inbox();
            report.passed = true;
            return;
        }
        if (scenario === 'lifecycle') {
            await lifecycle();
            report.passed = true;
            return;
        }
        await call('mobile_install_app', { device: serial, path: apk });
        // Reset only test permissions, never app data or a real account.
        for (const short of ['NEARBY_WIFI_DEVICES', 'POST_NOTIFICATIONS', 'RECEIVE_SMS']) {
            const permissionName = 'android.permission.' + short;
            adb('shell', 'pm', 'revoke', pkg, permissionName);
            adb('shell', 'pm', 'clear-permission-flags', pkg, permissionName, 'user-set', 'user-fixed');
        }
        await call('mobile_launch_app', { device: serial, packageName: pkg });
        await delay(1000);
        passed('Installed candidate and launched through the isolated MCP');

        await tapText('扫描 LAN');
        await delay(6000); // Deliberately longer than the scan itself: it must not run yet.
        await screenshot('permission-wait');
        await permission(false);
        let elements = await tree();
        assert(contains(elements, '本次未开始扫描'));
        assert(!contains(elements, '未发现设备'));
        await tapText('知道了');
        passed('Holding and denying Nearby permission does not launch discovery');

        await tapText('扫描 LAN');
        await permission(true);
        elements = await tree();
        assert(contains(elements, '正在扫描…'), 'Grant must resume the requested scan');
        await screenshot('scan-running');
        await delay(6000);
        elements = await tree();
        assert(contains(elements, '未发现设备') || contains(elements, '选择接收端'));
        await call('mobile_press_button', { device: serial, button: 'BACK' });
        elements = await tree();
        assert(contains(elements, '附近设备：✓ 已允许'));
        assert(contains(elements, '扫描 LAN'));
        passed('Grant resumes one finite scan and refreshes permission/button state');

        // Both permission rows use the same button label: locate it using the
        // current SMS label's vertical bounds, not fixed screen coordinates.
        let sms = elements.find(e => String(e.text || '').startsWith('短信权限：'));
        if (!sms) { await call('mobile_swipe_on_screen', { device: serial, direction: 'down', distance: 450 }); elements = await tree(); sms = elements.find(e => String(e.text || '').startsWith('短信权限：')); }
        assert(sms);
        const smsButton = elements.find(e => e.text === '申请' && Math.abs(e.coordinates.y - sms.coordinates.y) < 50);
        await tap(smsButton);
        await permission(true);
        elements = await tree();
        assert(contains(elements, '短信权限：✓ 已允许'));
        passed('SMS permission grant updates the visible row immediately');

        await tapText('启动接收');
        await permission(false);
        elements = await waitForText('LAN 已就绪');
        assert(contains(elements, '通知未开启'));
        await screenshot('receiver-ready-notifications-off');
        passed('Notification denial still starts LAN receiving with an honest warning');

        forwardPort = adb('forward', 'tcp:0', 'tcp:58123');
        assert(/^\d+$/.test(forwardPort));
        const address = `http://127.0.0.1:${forwardPort}`;
        const status = await fetch(address, { signal: AbortSignal.timeout(5000) });
        assert.equal(status.status, 200);
        const page = await status.text();
        const code = page.match(/>(\d{6})<\/b>/)?.[1];
        assert(code, 'Six-digit legacy pairing code missing');
        const id = randomUUID();
        const body = JSON.stringify({ id, from: 'Codex QA', text: 'Permission regression 246810', receivedAt: Date.now(), sim: -1, device: 'Codex isolated test' });
        const post = key => fetch(address + '/sms', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Xgy-Key': key }, body, signal: AbortSignal.timeout(5000) });
        assert.equal((await post('invalid')).status, 403);
        assert.equal((await post(code)).status, 200);
        assert.equal((await post(code)).status, 200);
        const history = adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl');
        assert.equal(history.split('\n').filter(line => { try { return JSON.parse(line).id === id; } catch { return false; } }).length, 1);
        report.syntheticMessageId = id;
        passed('Real LAN HTTP preserves pairing/authentication and persists one copy without notification permission');

        await tapText('停止接收');
        await delay(3500);
        elements = await tree();
        assert(contains(elements, '状态：未启动'));
        await assert.rejects(() => fetch(address, { signal: AbortSignal.timeout(4000) }));
        await screenshot('receiver-stopped');
        passed('Stop status agrees with the closed LAN listener');
        report.passed = true;
    } catch (error) {
        report.error = error.stack;
        process.exitCode = 1;
        try { await screenshot('failure'); } catch { }
    } finally {
        if (originalFontScale !== undefined) {
            try {
                if (originalFontScale === 'null') adb('shell', 'settings', 'delete', 'system', 'font_scale');
                else adb('shell', 'settings', 'put', 'system', 'font_scale', originalFontScale);
            } catch (error) { report.passed = false; report.error = 'Font restoration: ' + error.stack; process.exitCode = 1; }
        }
        if (inboxFixtureBackup) {
            try {
                adb('shell', 'am', 'force-stop', pkg);
                writeFixture('files/cloud-inbox.jsonl', inboxFixtureBackup.history);
                writeFixture('shared_prefs/msgdock_account.xml', inboxFixtureBackup.prefs);
                assert.equal(adb('shell', 'run-as', pkg, 'cat', 'files/cloud-inbox.jsonl'), inboxFixtureBackup.history.trim());
                assert.equal(adb('shell', 'run-as', pkg, 'cat', 'shared_prefs/msgdock_account.xml'), inboxFixtureBackup.prefs.trim());
                report.fixturesRestored = true;
            } catch (error) { report.passed = false; report.error = 'Fixture restoration: ' + error.stack; process.exitCode = 1; }
        }
        try { fs.writeFileSync(path.join(output, 'crash-log.txt'), adb('shell', 'logcat', '-b', 'crash', '-d')); } catch { }
        if (forwardPort) { try { adb('forward', '--remove', 'tcp:' + forwardPort); } catch { } }
        if (ownsReverse) { try { adb('reverse', '--remove', 'tcp:58123'); } catch { } }
        if (rotated) { try { await call('mobile_set_orientation', { device: serial, orientation: 'portrait' }); } catch { } }
        await client.close().catch(() => {});
        fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify({ passed: report.passed, checks: report.checks, error: report.error, output }, null, 2));
    }
})();
