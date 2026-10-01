package com.xgy.lansms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home-screen status wording and priority. Pure logic: it never changes sync behaviour. */
public class SyncHealthTest {
    private static final long NOW = 1_800_000_000_000L;

    private static SyncHealth.Input sender() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.smsPermission = true;
        in.loggedIn = true;
        in.now = NOW;
        return in;
    }

    private static boolean has(SyncHealth.Result r, SyncHealth.Action action) {
        for (SyncHealth.Issue issue : r.issues) if (issue.action == action) return true;
        return false;
    }

    @Test public void healthySenderIsOkWithRouteAndLastSync() {
        SyncHealth.Input in = sender();
        in.lanTargets = 1;
        in.lastSuccessAt = NOW - 5 * 60_000L;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.OK, r.level);
        assertEquals("同步正常", r.title);
        assertTrue(r.issues.isEmpty());
        assertTrue(r.detail.contains("转发：账号 + 1 台局域网设备"));
        assertTrue(r.detail.contains("最近一次同步：5 分钟前"));
    }

    @Test public void freshInstallAsksForSetupNotError() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.now = NOW;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.SETUP, r.level);
        assertEquals("还没开始同步", r.title);
    }

    @Test public void missingSmsPermissionWithRouteIsBlocking() {
        SyncHealth.Input in = sender();
        in.smsPermission = false;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.ERROR, r.level);
        assertTrue(has(r, SyncHealth.Action.GRANT_SMS));
    }

    @Test public void tabletWithoutTelephonyNeverAsksForSmsPermission() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.canReceiveSms = false;
        in.loggedIn = true;
        in.receiverEnabled = true;
        in.now = NOW;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertFalse(has(r, SyncHealth.Action.GRANT_SMS));
        assertEquals(SyncHealth.Level.OK, r.level);
        assertTrue(r.detail.contains("转发：本机无短信功能"));
    }

    @Test public void expiredLoginOutranksWarnings() {
        SyncHealth.Input in = sender();
        in.authRequired = true;
        in.batteryExempt = false;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.ERROR, r.level);
        assertEquals(SyncHealth.Action.RELOGIN, r.issues.get(0).action);
        assertTrue(has(r, SyncHealth.Action.BACKGROUND_GUIDE));
    }

    @Test public void pendingQueueShowsBusyNotError() {
        SyncHealth.Input in = sender();
        in.accountPending = 2;
        in.legacyPending = 1;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.BUSY, r.level);
        assertEquals("正在同步 3 条短信", r.title);
    }

    @Test public void offlineWithPendingWarnsAboutAutomaticResend() {
        SyncHealth.Input in = sender();
        in.accountPending = 4;
        in.online = false;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.WARN, r.level);
        assertTrue(r.issues.get(0).text.contains("4 条短信会在联网后自动补发"));
    }

    @Test public void unreadableQueueIsReportedNotCountedAsZero() {
        SyncHealth.Input in = sender();
        in.accountPending = -1;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.ERROR, r.level);
    }

    @Test public void receiverWithNotificationsOffWarnsWithFix() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.receiverEnabled = true;
        in.notificationsEnabled = false;
        in.now = NOW;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.WARN, r.level);
        assertTrue(has(r, SyncHealth.Action.ENABLE_NOTIFICATIONS));
    }

    @Test public void receiverFailureIsShownWithItsOwnStatus() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.receiverEnabled = true;
        in.receiverStatus = "LAN 监听失败；账号/云接收按各自设置继续";
        in.now = NOW;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.ERROR, r.level);
        assertTrue(has(r, SyncHealth.Action.SHOW_RECEIVER));
        assertFalse(SyncHealth.isReceiverFailure("✓ 运行中（LAN 已就绪）"));
    }

    @Test public void lanFailureOnlyWarnsWhenLanIsConfigured() {
        SyncHealth.Input in = sender();
        in.lanProblem = "Gaming-PC";
        assertTrue(SyncHealth.evaluate(in).issues.isEmpty());
        in.lanTargets = 1;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertEquals(SyncHealth.Level.WARN, r.level);
        assertTrue(r.issues.get(0).text.contains("Gaming-PC"));
        assertTrue(r.issues.get(0).text.contains("互联网同步不受影响"));
    }

    @Test public void permissionWithoutAnyTargetSuggestsSetup() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.smsPermission = true;
        in.now = NOW;
        SyncHealth.Result r = SyncHealth.evaluate(in);
        assertTrue(has(r, SyncHealth.Action.ADD_ROUTE));
        assertEquals(SyncHealth.Level.SETUP, r.level);
    }

    @Test public void receiverSourcesAreListed() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.receiverEnabled = true;
        in.loggedIn = true;
        in.accountReceive = true;
        in.cloudReceiverLinks = 1;
        in.now = NOW;
        assertTrue(SyncHealth.evaluate(in).detail.contains("接收：局域网 + 账号 + 配对云端 已开启"));
    }

    @Test public void relativeTime() {
        assertEquals("", SyncHealth.ago(0, NOW));
        assertEquals("刚刚", SyncHealth.ago(NOW - 10_000L, NOW));
        assertEquals("2 小时前", SyncHealth.ago(NOW - 2 * 3_600_000L, NOW));
        assertEquals("3 天前", SyncHealth.ago(NOW - 3 * 86_400_000L, NOW));
        assertEquals("", SyncHealth.ago(NOW + 10 * 60_000L, NOW));
    }

    @Test public void syncClockThrottlesWrites() {
        assertTrue(SyncClock.shouldWrite(0L, NOW));
        assertFalse(SyncClock.shouldWrite(NOW - 30_000L, NOW));
        assertTrue(SyncClock.shouldWrite(NOW - 60_000L, NOW));
        assertTrue(SyncClock.shouldWrite(NOW + 5_000L, NOW)); // clock moved backwards
    }
}
