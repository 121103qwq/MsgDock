package com.xgy.lansms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the scattered runtime facts (permissions, queues, receiver state) into one
 * home-screen summary plus a short list of fixable problems. Pure Java so the
 * wording and priority rules are covered by JVM tests; MainActivity only gathers
 * the inputs and renders the result. It never changes sync behaviour.
 */
final class SyncHealth {
    enum Level { OK, BUSY, SETUP, WARN, ERROR }

    enum Action { NONE, RELOGIN, GRANT_SMS, ENABLE_NOTIFICATIONS, BACKGROUND_GUIDE, SHOW_RECEIVER, ADD_ROUTE }

    static final class Issue {
        final Level level;
        final String text;
        final Action action;
        final String actionLabel;

        Issue(Level level, String text, Action action, String actionLabel) {
            this.level = level;
            this.text = text;
            this.action = action;
            this.actionLabel = actionLabel;
        }
    }

    static final class Input {
        boolean canReceiveSms = true;
        boolean smsPermission;
        boolean notificationsEnabled = true;
        boolean batteryExempt = true;
        boolean loggedIn;
        boolean authRequired;
        boolean accountReceive;
        boolean receiverEnabled;
        String receiverStatus = "";
        int lanTargets;
        int cloudSenderLinks;
        int cloudReceiverLinks;
        /** Negative counts mean the queue file could not be read. */
        int accountPending;
        int legacyPending;
        int deadLetters;
        boolean online = true;
        /** LAN receiver whose latest forward failed; empty when LAN is fine. */
        String lanProblem = "";
        long lastSuccessAt;
        long now;
    }

    static final class Result {
        final Level level;
        final String title;
        final String detail;
        final List<Issue> issues;

        Result(Level level, String title, String detail, List<Issue> issues) {
            this.level = level;
            this.title = title;
            this.detail = detail;
            this.issues = Collections.unmodifiableList(issues);
        }
    }

    private SyncHealth() {}

    static boolean sending(Input in) {
        return in.canReceiveSms && in.smsPermission && hasRoute(in);
    }

    static boolean hasRoute(Input in) {
        return in.loggedIn || in.lanTargets > 0 || in.cloudSenderLinks > 0;
    }

    static boolean receiving(Input in) {
        return in.receiverEnabled;
    }

    static Result evaluate(Input in) {
        List<Issue> issues = new ArrayList<>();
        boolean route = hasRoute(in);
        int pending = Math.max(0, in.accountPending) + Math.max(0, in.legacyPending);

        if (in.authRequired) {
            issues.add(new Issue(Level.ERROR, "账号授权已失效，账号同步已暂停。重新登录后会自动补传。",
                    Action.RELOGIN, "重新登录"));
        }
        if (in.canReceiveSms && route && !in.smsPermission) {
            issues.add(new Issue(Level.ERROR, "没有短信权限，本机新短信不会被转发。",
                    Action.GRANT_SMS, "允许短信权限"));
        }
        if (in.receiverEnabled && isReceiverFailure(in.receiverStatus)) {
            issues.add(new Issue(Level.ERROR, "接收服务异常：" + in.receiverStatus,
                    Action.SHOW_RECEIVER, "查看接收设置"));
        }
        if (in.accountPending < 0 || in.legacyPending < 0) {
            issues.add(new Issue(Level.ERROR, "待发送队列读取失败，原文件已保留。请查看高级设置里的云状态。",
                    Action.NONE, ""));
        }
        if (in.deadLetters > 0) {
            issues.add(new Issue(Level.WARN, in.deadLetters + " 条短信经配对云端多次发送失败，已停止重试。",
                    Action.NONE, ""));
        }
        if (sending(in) && in.lanTargets > 0 && in.lanProblem != null && !in.lanProblem.isEmpty()) {
            issues.add(new Issue(Level.WARN, "上次转发到“" + in.lanProblem + "”失败：电脑可能没开，或不在同一 Wi‑Fi。"
                    + (in.loggedIn || in.cloudSenderLinks > 0 ? "互联网同步不受影响。" : ""), Action.NONE, ""));
        }
        if (!in.online && pending > 0) {
            issues.add(new Issue(Level.WARN, "网络离线，" + pending + " 条短信会在联网后自动补发。",
                    Action.NONE, ""));
        }
        if (in.receiverEnabled && !in.notificationsEnabled) {
            issues.add(new Issue(Level.WARN, "通知已关闭，收到的短信只会存进收件箱，不会提醒。",
                    Action.ENABLE_NOTIFICATIONS, "开启通知"));
        }
        if ((sending(in) || receiving(in)) && !in.batteryExempt) {
            issues.add(new Issue(Level.WARN, "系统可能在后台限制 MsgDock，锁屏后短信可能延迟。",
                    Action.BACKGROUND_GUIDE, "后台设置教程"));
        }
        if (in.canReceiveSms && in.smsPermission && !route && !in.receiverEnabled) {
            issues.add(new Issue(Level.SETUP, "还没有转发目标：登录账号，或添加局域网电脑。",
                    Action.ADD_ROUTE, "去设置"));
        }

        Level level = Level.OK;
        for (Issue issue : issues) if (issue.level.ordinal() > level.ordinal()) level = issue.level;

        String title;
        if (level == Level.ERROR) title = "同步受阻，需要处理";
        else if (level == Level.WARN) title = "同步可用，有提醒";
        else if (!sending(in) && !receiving(in)) {
            level = Level.SETUP;
            title = "还没开始同步";
        } else if (pending > 0) {
            level = Level.BUSY;
            title = "正在同步 " + pending + " 条短信";
        } else title = "同步正常";

        return new Result(level, title, detail(in, pending), issues);
    }

    static boolean isReceiverFailure(String status) {
        return status != null && status.contains("失败");
    }

    private static String detail(Input in, int pending) {
        StringBuilder out = new StringBuilder();
        if (!in.canReceiveSms) out.append("转发：本机无短信功能");
        else if (sending(in)) out.append("转发：").append(routeSummary(in));
        else if (!in.smsPermission) out.append("转发：未开启（缺短信权限）");
        else out.append("转发：未设置目标");

        out.append("\n接收：");
        if (!in.receiverEnabled) out.append("未开启");
        else {
            List<String> sources = new ArrayList<>();
            sources.add("局域网");
            if (in.loggedIn && in.accountReceive && !in.authRequired) sources.add("账号");
            if (in.cloudReceiverLinks > 0) sources.add("配对云端");
            out.append(join(sources)).append(" 已开启");
        }
        if (pending > 0) out.append("\n待发送：").append(pending).append(" 条");
        String ago = ago(in.lastSuccessAt, in.now);
        if (!ago.isEmpty()) out.append("\n最近一次同步：").append(ago);
        return out.toString();
    }

    private static String routeSummary(Input in) {
        List<String> parts = new ArrayList<>();
        if (in.loggedIn) parts.add(in.authRequired ? "账号（已暂停）" : "账号");
        if (in.lanTargets > 0) parts.add(in.lanTargets + " 台局域网设备");
        if (in.cloudSenderLinks > 0) parts.add(in.cloudSenderLinks + " 条配对云端");
        return join(parts);
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(" + ");
            out.append(parts.get(i));
        }
        return out.toString();
    }

    /** Coarse, locale-free relative time; empty when unknown or in the future. */
    static String ago(long at, long now) {
        if (at <= 0 || now <= 0 || at > now + 60_000L) return "";
        long seconds = Math.max(0L, (now - at) / 1000L);
        if (seconds < 60) return "刚刚";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return (hours / 24) + " 天前";
    }
}
