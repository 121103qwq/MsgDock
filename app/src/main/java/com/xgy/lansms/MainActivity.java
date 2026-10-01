package com.xgy.lansms;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQUEST_SMS = 10;
    private static final int REQUEST_NOTIFICATIONS = 11;
    private static final int REQUEST_NEARBY = 12;
    private static final int REQUEST_SCAN = 13;
    private int pendingPermission;
    private boolean scanning;
    private Thread scanThread;
    // UI references
    private TextView relayUrlEdit;
    private TextView cloudStatusText, machineCodeText, backupStatusText;
    private TextView smsPermissionText, nearbyPermissionText, receiverStatusText;
    private TextView accountStatusText;
    private EditText accountUsernameEdit, accountEmailEdit, accountPasswordEdit;
    private CheckBox accountReceiveCheck;
    private final Handler accountUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable accountUiRefresh = new Runnable() {
        @Override public void run() {
            if (!activityAlive()) return;
            accountStatusText.setText(accountSummary());
            receiverStatusText.setText(receiverStatus());
            renderAccountMode();
            renderStatus();
            renderRecentIfChanged();
            accountUiHandler.postDelayed(this, 3000L);
        }
    };
    private LinearLayout cloudLinksContainer, targetsContainer;
    private Button cloudPairSenderBtn, cloudPairReceiverBtn;
    private View nearbyPermissionRow;
    // Status-first home screen
    private View statusDot, loginGroup, sessionGroup, advancedContainer;
    private TextView statusTitle, statusDetail;
    private LinearLayout issuesContainer, recentContainer;
    private Button advancedToggle;
    private boolean advancedExpanded;
    private String recentStamp = "";
    private String statusKey = "";

    private final List<TargetStore.Target> discovered = Collections.synchronizedList(new ArrayList<>());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            pendingPermission = b.getInt("pending_permission", 0);
            advancedExpanded = b.getBoolean("advanced_expanded", false);
        }
        setContentView(R.layout.activity_main);
        WindowLayout.apply(this);
        
        Notifications.ensureChannels(this);
        ReceiverService.migrateReceiverEnabled(this);
        ReceiverService.ensureStarted(this);
        CloudSyncJobService.schedule(this);
        
        initViews();
        setupListeners();
        render();
        
        DeviceBackupManager.onAppStarted(this, (success, message) -> {
            if (!activityAlive()) return;
            if (!success && message != null && message.contains("重新登记")) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
            runOnUiThread(this::render);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        ReceiverService.ensureStarted(this);
        render();
        accountUiHandler.post(accountUiRefresh);
    }

    @Override protected void onPause() {
        accountUiHandler.removeCallbacks(accountUiRefresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("pending_permission", pendingPermission);
        state.putBoolean("advanced_expanded", advancedExpanded);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (scanThread != null) scanThread.interrupt();
        super.onDestroy();
    }

    private void askPermission(String permission, int requestCode) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            render();
            return;
        }
        if (pendingPermission != 0) return;
        pendingPermission = requestCode;
        requestPermissions(new String[]{permission}, requestCode);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != pendingPermission) return;
        pendingPermission = 0;
        if (!activityAlive()) return;
        if (results.length == 0) {
            Toast.makeText(this, "已取消权限申请，请在需要时重试", Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        boolean granted = results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATIONS) {
            // Notification denial must not prevent receiving or saving history.
            startReceiverNow();
            if (!granted) Toast.makeText(this, "通知未开启，短信仍会保存在本机历史；可在应用设置中开启通知", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_SCAN && granted) {
            scanLan();
        } else if (!granted) {
            String permission = requestCode == REQUEST_SMS ? Manifest.permission.RECEIVE_SMS : Manifest.permission.NEARBY_WIFI_DEVICES;
            String explanation = requestCode == REQUEST_SMS
                ? "短信权限未允许，无法监听本机新短信；接收其他设备的短信不受影响。"
                : "附近设备权限未允许，本次未开始扫描。也可以手动添加接收端 IP。";
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("权限未开启")
                .setMessage(explanation).setNegativeButton("知道了", null);
            if (!shouldShowRequestPermissionRationale(permission)) {
                dialog.setPositiveButton("应用设置", (d, w) -> openAppSettings());
            }
            dialog.show();
        }
        render();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())));
    }

    private void initViews() {
        relayUrlEdit = findViewById(R.id.edit_relay_url);
        cloudStatusText = findViewById(R.id.text_cloud_status);
        machineCodeText = findViewById(R.id.text_machine_code);
        backupStatusText = findViewById(R.id.text_backup_status);
        smsPermissionText = findViewById(R.id.text_sms_permission);
        nearbyPermissionText = findViewById(R.id.text_nearby_permission);
        receiverStatusText = findViewById(R.id.text_receiver_status);
        accountStatusText = findViewById(R.id.text_account_status);
        accountUsernameEdit = findViewById(R.id.edit_account_username);
        accountEmailEdit = findViewById(R.id.edit_account_email);
        accountPasswordEdit = findViewById(R.id.edit_account_password);
        accountReceiveCheck = findViewById(R.id.check_account_receive);
        accountReceiveCheck.setChecked(AccountStore.receiveEnabled(this));
        cloudLinksContainer = findViewById(R.id.container_cloud_links);
        targetsContainer = findViewById(R.id.container_targets);
        cloudPairSenderBtn = findViewById(R.id.btn_cloud_pair_sender);
        cloudPairReceiverBtn = findViewById(R.id.btn_cloud_pair_receiver);
        nearbyPermissionRow = findViewById(R.id.row_nearby_permission);
        statusDot = findViewById(R.id.view_status_dot);
        statusTitle = findViewById(R.id.text_status_title);
        statusDetail = findViewById(R.id.text_status_detail);
        issuesContainer = findViewById(R.id.container_issues);
        recentContainer = findViewById(R.id.container_recent);
        loginGroup = findViewById(R.id.group_account_login);
        sessionGroup = findViewById(R.id.group_account_session);
        advancedContainer = findViewById(R.id.container_advanced);
        advancedToggle = findViewById(R.id.btn_toggle_advanced);
    }

    private void setupListeners() {
        findViewById(R.id.btn_background_guide).setOnClickListener(v ->
            startActivity(new Intent(this, BackgroundGuideActivity.class)));
        advancedToggle.setOnClickListener(v -> {
            advancedExpanded = !advancedExpanded;
            renderAdvanced();
        });
        cloudPairSenderBtn.setOnClickListener(v -> cloudPairSender());
        cloudPairReceiverBtn.setOnClickListener(v -> cloudPairReceiver());
        
        findViewById(R.id.btn_copy_machine_code).setOnClickListener(v -> copyMachineCode());
        findViewById(R.id.btn_sync_recover).setOnClickListener(v -> syncOrRecover());
        findViewById(R.id.btn_delete_device).setOnClickListener(v -> confirmDeleteDevice());
        
        findViewById(R.id.btn_request_sms).setOnClickListener(v -> 
            askPermission(Manifest.permission.RECEIVE_SMS, REQUEST_SMS));
        findViewById(R.id.btn_request_nearby).setOnClickListener(v -> 
            askPermission(Manifest.permission.NEARBY_WIFI_DEVICES, REQUEST_NEARBY));
        
        findViewById(R.id.btn_scan_lan).setOnClickListener(v -> scanLan());
        findViewById(R.id.btn_manual_add).setOnClickListener(v -> manualAdd());
        findViewById(R.id.btn_send_test).setOnClickListener(v -> sendTest());
        
        findViewById(R.id.btn_start_receiver).setOnClickListener(v -> startReceiver());
        findViewById(R.id.btn_stop_receiver).setOnClickListener(v -> stopReceiver());
        
        findViewById(R.id.btn_battery_optimize).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.btn_app_settings).setOnClickListener(v -> openAppSettings());
        findViewById(R.id.btn_open_shizuku).setOnClickListener(v -> openShizuku());
        findViewById(R.id.btn_copy_adb).setOnClickListener(v -> copyAdbCommands());

        findViewById(R.id.btn_account_login).setOnClickListener(v -> accountLogin());
        findViewById(R.id.btn_account_register).setOnClickListener(v -> accountRegister());
        findViewById(R.id.btn_account_logout).setOnClickListener(v -> accountLogout());
        accountReceiveCheck.setOnCheckedChangeListener((button, enabled) -> {
            AccountStore.setReceiveEnabled(this, enabled);
            if (enabled && AccountStore.hasAccount(this)) startReceiver();
            render();
        });
        findViewById(R.id.btn_account_inbox).setOnClickListener(v -> showInbox());
        findViewById(R.id.btn_account_refresh).setOnClickListener(v -> {
            if (!AccountStore.hasAccount(this)) { Toast.makeText(this, "请先登录账号", Toast.LENGTH_SHORT).show(); return; }
            if (accountReceiveCheck.isChecked()) startReceiver();
            else accountReceiveCheck.setChecked(true); // The change listener starts it once.
            CloudRelay.executor().execute(() -> {
                boolean ok = AccountApi.pollInbox(getApplicationContext());
                runOnUiThread(() -> {
                    if (!activityAlive()) return;
                    Toast.makeText(this, ok ? "已收取，后台会继续补齐" : "暂未连接，将自动重试", Toast.LENGTH_SHORT).show();
                    render();
                    showInbox();
                });
            });
        });
    }

    private void render() {
        // Cloud relay
        relayUrlEdit.setText(RelayHttp.PRIMARY);
        ((TextView) findViewById(R.id.text_backup_relay_url)).setText(RelayHttp.BACKUP);
        cloudStatusText.setText(CloudRelay.statusText(this));
        
        // Device management
        machineCodeText.setText(DeviceBackupManager.machineCode(this));
        backupStatusText.setText(DeviceBackupManager.statusText(this));
        renderCloudLinks();
        
        // Permissions
        boolean sms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        smsPermissionText.setText("短信权限：" + (sms ? "✓ 已允许" : "✗ 未允许"));
        
        if (Build.VERSION.SDK_INT >= 33) {
            nearbyPermissionRow.setVisibility(View.VISIBLE);
            boolean nearby = checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            nearbyPermissionText.setText("附近设备：" + (nearby ? "✓ 已允许" : "○ 未允许"));
        } else {
            nearbyPermissionRow.setVisibility(View.GONE);
        }
        
        // Targets
        renderTargets();
        
        // Receiver
        receiverStatusText.setText(receiverStatus());
        accountStatusText.setText(accountSummary());
        renderAccountMode();
        renderAdvanced();
        renderStatus();
        renderRecentIfChanged();
    }

    private void renderAdvanced() {
        advancedContainer.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        advancedToggle.setText(advancedExpanded ? "收起高级设置" : "展开高级设置（配对云端、链路恢复、系统权限）");
    }

    /** Login form only while logged out; refresh/logout only while logged in. */
    private void renderAccountMode() {
        boolean loggedIn = AccountStore.hasAccount(this);
        boolean relogin = loggedIn && AccountStore.authRequired(this);
        loginGroup.setVisibility(!loggedIn || relogin ? View.VISIBLE : View.GONE);
        sessionGroup.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_account_refresh).setVisibility(relogin ? View.GONE : View.VISIBLE);
        if (relogin && accountUsernameEdit.getText().length() == 0) {
            String name = AccountStore.username(this);
            accountUsernameEdit.setText(name.isEmpty() ? AccountStore.email(this) : name);
        }
        accountEmailEditVisibility(!loggedIn);
    }

    private void accountEmailEditVisibility(boolean visible) {
        accountEmailEdit.setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_account_register).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Human summary for the account card; diagnostics stay in the status card and advanced section. */
    private String accountSummary() {
        if (!AccountStore.hasAccount(this)) return "未登录";
        String name = AccountStore.username(this);
        if (name.isEmpty()) name = AccountStore.email(this);
        if (name.isEmpty()) name = "已登录账号";
        StringBuilder out = new StringBuilder("已登录：").append(name);
        if (AccountStore.authRequired(this)) {
            return out.append("\n授权已失效，请重新输入密码登录。待上传的短信会保留。").toString();
        }
        int pending = AccountOutboxStore.count(this);
        out.append("\n上传：").append(!AccountStore.hasDeviceToken(this) ? "正在注册本机"
            : pending > 0 ? pending + " 条等待上传" : pending < 0 ? "队列读取失败" : "已全部上传");
        out.append("\n接收：").append(AccountStore.receiveEnabled(this)
            ? AccountStore.prefs(this).getString("receive_status", "等待接收服务") : "未开启");
        return out.toString();
    }

    private SyncHealth.Input healthInput() {
        SyncHealth.Input in = new SyncHealth.Input();
        in.canReceiveSms = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        in.smsPermission = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        in.notificationsEnabled = notifications == null || notifications.areNotificationsEnabled();
        PowerManager power = getSystemService(PowerManager.class);
        in.batteryExempt = power == null || power.isIgnoringBatteryOptimizations(getPackageName());
        in.loggedIn = AccountStore.hasAccount(this);
        in.authRequired = in.loggedIn && AccountStore.authRequired(this);
        in.accountReceive = AccountStore.receiveEnabled(this);
        in.receiverEnabled = TargetStore.prefs(this).getBoolean(ReceiverService.PREF_RECEIVER_ENABLED, false);
        in.receiverStatus = ReceiverService.statusText();
        in.lanTargets = TargetStore.load(this).size();
        in.cloudSenderLinks = CloudConfigStore.senderLinks(this).size();
        in.cloudReceiverLinks = CloudConfigStore.receiverLinks(this).size();
        in.accountPending = in.loggedIn ? AccountOutboxStore.count(this) : 0;
        in.legacyPending = CloudOutboxStore.count(this);
        in.deadLetters = Math.max(0, CloudOutboxStore.deadLetterCount(this));
        in.online = CloudRelay.hasNetwork(this);
        in.lanProblem = SyncClock.lanProblem(this);
        in.lastSuccessAt = SyncClock.lastSuccess(this);
        in.now = System.currentTimeMillis();
        return in;
    }

    private void renderStatus() {
        SyncHealth.Result health = SyncHealth.evaluate(healthInput());
        StringBuilder key = new StringBuilder(health.level.name()).append(health.title).append(health.detail);
        for (SyncHealth.Issue issue : health.issues) key.append('|').append(issue.text);
        // Rebuilding rows every 3 s would steal focus/ripples from the buttons; skip when unchanged.
        if (key.toString().equals(statusKey)) return;
        statusKey = key.toString();
        statusTitle.setText(health.title);
        statusDetail.setText(health.detail);
        statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(levelColor(health.level))));
        issuesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (SyncHealth.Issue issue : health.issues) {
            View row = inflater.inflate(R.layout.item_issue, issuesContainer, false);
            TextView text = row.findViewById(R.id.text_issue);
            text.setText(issue.text);
            text.setTextColor(getColor(issue.level == SyncHealth.Level.ERROR ? R.color.accent_error
                : issue.level == SyncHealth.Level.WARN ? R.color.accent_warning : R.color.text_primary));
            Button action = row.findViewById(R.id.btn_issue_action);
            if (issue.action == SyncHealth.Action.NONE) action.setVisibility(View.GONE);
            else {
                action.setText(issue.actionLabel);
                action.setOnClickListener(v -> runIssueAction(issue.action));
            }
            issuesContainer.addView(row);
        }
        issuesContainer.setVisibility(health.issues.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static int levelColor(SyncHealth.Level level) {
        switch (level) {
            case OK: return R.color.accent_success;
            case BUSY: return R.color.accent_primary;
            case WARN: return R.color.accent_warning;
            case ERROR: return R.color.accent_error;
            default: return R.color.text_tertiary;
        }
    }

    private void runIssueAction(SyncHealth.Action action) {
        switch (action) {
            case RELOGIN:
                scrollTo(R.id.card_account);
                accountPasswordEdit.requestFocus();
                break;
            case GRANT_SMS:
                askPermission(Manifest.permission.RECEIVE_SMS, REQUEST_SMS);
                break;
            case ENABLE_NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    askPermission(Manifest.permission.POST_NOTIFICATIONS, REQUEST_NOTIFICATIONS);
                } else {
                    try {
                        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (ActivityNotFoundException e) { openAppSettings(); }
                }
                break;
            case BACKGROUND_GUIDE:
                startActivity(new Intent(this, BackgroundGuideActivity.class));
                break;
            case SHOW_RECEIVER:
                scrollTo(R.id.card_receiver);
                break;
            case ADD_ROUTE:
                scrollTo(R.id.card_account);
                break;
            default:
                break;
        }
    }

    private void scrollTo(int viewId) {
        View target = findViewById(viewId);
        ScrollView scroll = findViewById(R.id.main_scroll);
        if (target != null && scroll != null) scroll.post(() -> scroll.smoothScrollTo(0, target.getTop()));
    }

    /** Re-reads the inbox only when the history file actually changed (size or mtime). */
    private void renderRecentIfChanged() {
        java.io.File file = new java.io.File(getFilesDir(), "cloud-inbox.jsonl");
        String user = AccountStore.userId(this);
        String stamp = user + "|" + file.length() + "|" + file.lastModified();
        if (stamp.equals(recentStamp)) return;
        recentStamp = stamp;
        CloudRelay.executor().execute(() -> {
            List<org.json.JSONObject> rows;
            try { rows = CloudInboxStore.localHistory(getFilesDir(), user, 3); }
            catch (Exception e) { rows = Collections.emptyList(); }
            final List<org.json.JSONObject> recent = rows;
            runOnUiThread(() -> {
                if (activityAlive() && user.equals(AccountStore.userId(this))) renderRecent(recent, user);
            });
        });
    }

    private void renderRecent(List<org.json.JSONObject> rows, String user) {
        recentContainer.removeAllViews();
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有收到短信。转发或接收的短信会显示在这里，最新的在最上面。");
            empty.setTextAppearance(R.style.Text_Body);
            recentContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (org.json.JSONObject row : rows) {
            View item = inflater.inflate(R.layout.item_recent_sms, recentContainer, false);
            String body = row.optString("text", "");
            ((TextView) item.findViewById(R.id.text_recent_from)).setText(row.optString("from", "短信"));
            ((TextView) item.findViewById(R.id.text_recent_body)).setText(body.replace('\n', ' '));
            ((TextView) item.findViewById(R.id.text_recent_meta)).setText(inboxMetadata(row));
            String code = OtpCode.find(body);
            Button copy = item.findViewById(R.id.btn_recent_copy_code);
            if (!code.isEmpty()) {
                copy.setVisibility(View.VISIBLE);
                copy.setText("复制 " + code);
                copy.setContentDescription("复制验证码 " + code);
                copy.setOnClickListener(v -> copyInboxText(code, user));
            }
            item.setOnClickListener(v -> showInboxDetail(row, user));
            recentContainer.addView(item);
        }
    }

    private void renderCloudLinks() {
        cloudLinksContainer.removeAllViews();
        List<CloudConfigStore.CloudLink> links = CloudConfigStore.loadLinks(this);
        
        if (links.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无云链路");
            empty.setTextSize(14);
            empty.setPadding(16, 16, 16, 16);
            cloudLinksContainer.addView(empty);
            return;
        }
        
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CloudConfigStore.CloudLink link : links) {
            View item = inflater.inflate(R.layout.item_cloud_link, cloudLinksContainer, false);
            
            TextView nameText = item.findViewById(R.id.text_link_name);
            TextView detailText = item.findViewById(R.id.text_link_detail);
            Button removeBtn = item.findViewById(R.id.btn_remove_link);
            
            String name = link.peerName.isEmpty() ? link.peerDeviceId : link.peerName;
            nameText.setText((link.isSender() ? "发送 → " : "接收 ← ") + name);
            detailText.setText("本机设备 ID：" + link.deviceId + "\n对端：" + link.peerDeviceId);
            
            removeBtn.setOnClickListener(v -> confirmRemoveLink(link));
            cloudLinksContainer.addView(item);
        }
    }

    private void renderTargets() {
        targetsContainer.removeAllViews();
        List<TargetStore.Target> ts = TargetStore.load(this);
        
        if (ts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无 LAN 接收端");
            empty.setTextSize(14);
            empty.setPadding(16, 16, 16, 16);
            targetsContainer.addView(empty);
            return;
        }
        
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < ts.size(); i++) {
            TargetStore.Target t = ts.get(i);
            View item = inflater.inflate(R.layout.item_target, targetsContainer, false);
            
            TextView nameText = item.findViewById(R.id.text_target_name);
            TextView addressText = item.findViewById(R.id.text_target_address);
            Button removeBtn = item.findViewById(R.id.btn_remove_target);
            
            nameText.setText(t.name);
            addressText.setText(t.host + ":" + t.port);
            
            final int idx = i;
            removeBtn.setOnClickListener(v -> {
                List<TargetStore.Target> now = TargetStore.load(this);
                if (idx < now.size()) {
                    now.remove(idx);
                    TargetStore.save(this, now);
                    renderTargets();
                }
            });
            
            targetsContainer.addView(item);
        }
    }


    private void cloudPairSender() {
        String relayUrl = relayUrlEdit.getText().toString();
        EditText code = new EditText(this);
        code.setHint("接收端显示的 6 位云配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        
        new AlertDialog.Builder(this)
            .setTitle("云端发送配对")
            .setMessage("请先在 Windows 或 Android 云接收端创建配对会话，再输入它显示的 6 位数字。")
            .setView(code)
            .setPositiveButton("配对", (d, w) -> {
                String value = code.getText().toString().trim();
                if (!value.matches("\\d{6}")) {
                    Toast.makeText(this, "云配对码应为 6 位数字", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    CloudRelay.saveRelayUrl(this, relayUrl);
                } catch (Exception e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "正在配对…", Toast.LENGTH_SHORT).show();
                CloudRelay.pair(this, relayUrl, value, (success, message) -> runOnUiThread(() -> {
                    if (!activityAlive()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    render();
                }));
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void cloudPairReceiver() {
        String relayUrl = relayUrlEdit.getText().toString();
        try {
            CloudRelay.saveRelayUrl(this, relayUrl);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        
        Toast.makeText(this, "正在生成云接收配对码…", Toast.LENGTH_SHORT).show();
        CloudRelay.startReceiverPairing(this, relayUrl, new CloudRelay.ReceiverPairCallback() {
            @Override public void started(String code) {
                runOnUiThread(() -> {
                    if (!activityAlive()) return;
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Android 云接收配对码")
                        .setMessage("请在 Windows/Android 发送端输入此 6 位配对码：\n\n" + code + 
                            "\n\n保持本页或接收服务运行，配对完成后会自动保存并开始接收。")
                        .setPositiveButton("知道了", null)
                        .show();
                });
            }
            @Override public void completed(boolean success, String message) {
                runOnUiThread(() -> {
                    if (!activityAlive()) return;
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    if (success) startReceiver();
                    else render();
                });
            }
        });
    }

    private void accountLogin() {
        setAccountBusy(true);
        String username = accountUsernameEdit.getText().toString().trim();
        String email = accountEmailEdit.getText().toString().trim();
        String password = accountPasswordEdit.getText().toString();
        String identifier = username.isEmpty() ? email : username;
        Toast.makeText(this, "正在登录 MsgDock…", Toast.LENGTH_SHORT).show();
        AccountApi.login(this, identifier, password, (success, message) -> {
            if (!activityAlive()) return;
            finishAccountLogin(success);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private void accountRegister() {
        setAccountBusy(true);
        String username = accountUsernameEdit.getText().toString().trim();
        String email = accountEmailEdit.getText().toString().trim();
        String password = accountPasswordEdit.getText().toString();
        Toast.makeText(this, "正在注册 MsgDock…", Toast.LENGTH_SHORT).show();
        AccountApi.register(this, username, email, password, (success, message) -> {
            if (!activityAlive()) return;
            finishAccountLogin(success);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private void accountLogout() {
        setAccountBusy(true);
        AccountApi.logout(this, (success, message) -> {
            if (!activityAlive()) return;
            setAccountBusy(false);
            accountReceiveCheck.setChecked(false);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private void setAccountBusy(boolean busy) {
        findViewById(R.id.btn_account_login).setEnabled(!busy);
        findViewById(R.id.btn_account_register).setEnabled(!busy);
        findViewById(R.id.btn_account_logout).setEnabled(!busy);
    }

    private void finishAccountLogin(boolean success) {
        setAccountBusy(false);
        if (success) {
            accountPasswordEdit.setText("");
            if (AccountStore.receiveEnabled(this)) startReceiver();
        }
    }

    private void showInbox() {
        String user = AccountStore.userId(this);
        CloudRelay.executor().execute(() -> {
            try {
                List<org.json.JSONObject> rows = CloudInboxStore.localHistory(getFilesDir(), user, 200);
                runOnUiThread(() -> {
                    if (!activityAlive() || !user.equals(AccountStore.userId(this))) return;
                    if (rows.isEmpty()) {
                        new AlertDialog.Builder(this).setTitle("本机收件箱")
                            .setMessage("暂无已保存的短信。局域网和配对云端收到的短信无需登录即可查看；登录后也会显示当前账号已同步到本机的历史。")
                            .setPositiveButton("知道了", null).show();
                        return;
                    }
                    String[] labels = new String[rows.size()];
                    for (int i = 0; i < rows.size(); i++) {
                        org.json.JSONObject row = rows.get(i);
                        String body = row.optString("text", "").replace('\n', ' ');
                        labels[i] = row.optString("from") + "\n" + inboxMetadata(row) + "\n"
                                + (body.length() > 60 ? body.substring(0, 60) + "…" : body);
                    }
                    new AlertDialog.Builder(this).setTitle("本机收件箱（最近 " + rows.size() + " 条）")
                        .setItems(labels, (dialog, which) -> showInboxDetail(rows.get(which), user))
                        .setNegativeButton("关闭", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (activityAlive()) Toast.makeText(this, "读取收件箱失败，请重试", Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void showInboxDetail(org.json.JSONObject row, String user) {
        if (!activityAlive() || !user.equals(AccountStore.userId(this))) return;
        String body = row.optString("text");
        AlertDialog.Builder detail = new AlertDialog.Builder(this).setTitle(row.optString("from"))
            .setMessage(inboxMetadata(row) + "\n\n" + body)
            .setPositiveButton("复制全文", (d, w) -> copyInboxText(body, user)).setNegativeButton("关闭", null);
        // Prefer an explicit OTP; fall back to the legacy digit rule so manual copy still works.
        String value = OtpCode.find(body);
        if (value.isEmpty()) {
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)").matcher(body);
            if (code.find()) value = code.group(1);
        }
        if (!value.isEmpty()) {
            String copied = value;
            detail.setNeutralButton("复制验证码 " + copied, (d, w) -> copyInboxText(copied, user));
        }
        detail.show();
    }

    private String inboxMetadata(org.json.JSONObject row) {
        String source = row.optString("source");
        String label = "lan".equals(source) ? "局域网" : "cloud".equals(source) ? "配对云端" : "账号同步";
        return row.optString("device", "未知设备") + " · " + label + " · "
            + java.text.DateFormat.getDateTimeInstance().format(new Date(row.optLong("receivedAt")));
    }

    private void copyInboxText(String text, String user) {
        if (!activityAlive() || !user.equals(AccountStore.userId(this))) return;
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("MsgDock", text);
            if (Build.VERSION.SDK_INT >= 33) {
                // SMS bodies and codes are sensitive: keep them out of the system copy preview.
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
        }
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void copyMachineCode() {
        String code = DeviceBackupManager.machineCode(this);
        if (code.contains("未登记") || code.contains("计算中") || code.contains("不可用")) {
            Toast.makeText(this, "设备编号仍不可用，请稍后再试", Toast.LENGTH_LONG).show();
            return;
        }
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("Xgy 机器码", code));
        Toast.makeText(this, "机器码已复制", Toast.LENGTH_SHORT).show();
    }

    private void syncOrRecover() {
        Toast.makeText(this, "正在按机器码检查云端链路…", Toast.LENGTH_SHORT).show();
        DeviceBackupManager.syncOrRecover(this, (ok, message) -> {
            if (!activityAlive()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private void confirmRemoveLink(CloudConfigStore.CloudLink link) {
        String name = link.peerName.isEmpty() ? link.peerDeviceId : link.peerName;
        new AlertDialog.Builder(this)
            .setTitle("删除云链路？")
            .setMessage("将撤销'" + name + "'的云端链路，并清理关联待发送消息。此操作不可自动恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除", (d, w) -> {
                Toast.makeText(this, "正在撤销云链路…", Toast.LENGTH_SHORT).show();
                DeviceBackupManager.removeLink(this, link, (ok, message) -> {
                    if (!activityAlive()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    render();
                });
            })
            .show();
    }

    private void confirmDeleteDevice() {
        new AlertDialog.Builder(this)
            .setTitle("删除云端机器记录？")
            .setMessage("将撤销本机全部云链路、删除加密备份并清空本机云链路。之后不会自动重新上传，必须手动重新登记。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除全部", (d, w) -> {
                Toast.makeText(this, "正在删除云端机器记录…", Toast.LENGTH_SHORT).show();
                DeviceBackupManager.deleteDevice(this, (ok, message) -> {
                    if (!activityAlive()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    render();
                });
            })
            .show();
    }

    private String receiverStatus() {
        boolean on = TargetStore.prefs(this).getBoolean(ReceiverService.PREF_RECEIVER_ENABLED, false);
        String pending = CloudRelay.pendingReceiverCode(this);
        String status = on ? ReceiverService.statusText()
            : (ReceiverService.isRunningOrStarting() ? "正在停止…" : "未启动");
        NotificationManager notifications = getSystemService(NotificationManager.class);
        boolean notify = notifications != null && notifications.areNotificationsEnabled();
        return "状态：" + status +
            "\n地址：http://" + ReceiverService.localIpv4(this) + ":58123" +
            "\nLAN 配对码：" + TargetStore.ensurePairCode(this) +
            "\n云接收链路：" + CloudConfigStore.receiverLinks(this).size() + " 条" +
            (pending.isEmpty() ? "" : "\n云接收配对码：" + pending) +
            (notify ? "" : "\n通知未开启：短信仍保存到本机历史，可在应用设置开启通知");
    }

    private void startReceiver() {
        if (Build.VERSION.SDK_INT >= 33 && 
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askPermission(Manifest.permission.POST_NOTIFICATIONS, REQUEST_NOTIFICATIONS);
            return;
        }
        startReceiverNow();
    }

    private void startReceiverNow() {
        if (!TargetStore.prefs(this).edit().putBoolean(ReceiverService.PREF_RECEIVER_ENABLED, true).commit()) {
            Toast.makeText(this, "启动设置保存失败，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        boolean requested = ReceiverService.ensureStarted(this);
        Toast.makeText(this, requested ? ReceiverService.statusText() : "启动失败，请重试或检查后台限制", Toast.LENGTH_LONG).show();
        render();
    }

    private void stopReceiver() {
        if (!TargetStore.prefs(this).edit().putBoolean(ReceiverService.PREF_RECEIVER_ENABLED, false).commit()) {
            Toast.makeText(this, "停止设置保存失败，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        BootReceiver.cancelReceiverRestart(this);
        if (ReceiverService.isRunningOrStarting()) {
            Intent stop = new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_STOP);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(stop);
                else startService(stop);
            } catch (RuntimeException e) {
                stopService(new Intent(this, ReceiverService.class));
            }
        }
        render();
    }

    private void scanLan() {
        if (scanning) return;
        if (Build.VERSION.SDK_INT >= 33 && 
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            askPermission(Manifest.permission.NEARBY_WIFI_DEVICES, REQUEST_SCAN);
            return;
        }
        scanning = true;
        Button scanButton = findViewById(R.id.btn_scan_lan);
        scanButton.setEnabled(false);
        scanButton.setText("正在扫描…");
        Toast.makeText(this, "正在扫描约 5 秒…", Toast.LENGTH_SHORT).show();
        discovered.clear();
        
        scanThread = new Thread(() -> {
            long end = SystemClock.elapsedRealtime() + 5200;
            String failure = null;
            Set<String> seen = new HashSet<>();
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(ReceiverService.DISCOVERY_PORT));
                LanNet.bindWifi(this, ds);
                ds.setSoTimeout(600);
                byte[] buf = new byte[1024];
                
                while (!Thread.currentThread().isInterrupted() && SystemClock.elapsedRealtime() < end) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String s = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                        String[] f = s.split("\\|", 4);
                        if (f.length == 4 && "XGY_SMS_V1".equals(f[0])) {
                            int port;
                            try { port = Integer.parseInt(f[3]); } catch (NumberFormatException invalidPacket) { continue; }
                            if (port < 1 || port > 65535) continue;
                            String key = f[2] + ":" + f[3];
                            if (seen.add(key)) {
                                discovered.add(new TargetStore.Target(f[1], f[2], port, ""));
                            }
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                failure = "扫描失败，请检查 Wi-Fi 后重试，也可以手动添加接收端 IP。";
                android.util.Log.w("XgyLanSms", "LAN discovery failed", e);
            }
            if (Thread.currentThread().isInterrupted()) return;
            final String error = failure;
            runOnUiThread(() -> {
                if (!activityAlive()) return;
                scanning = false;
                scanThread = null;
                scanButton.setEnabled(true);
                scanButton.setText("扫描 LAN");
                if (error != null) new AlertDialog.Builder(this).setTitle("无法扫描")
                    .setMessage(error).setPositiveButton("知道了", null).show();
                else showDiscovered();
            });
        }, "MsgDock-LAN-scan");
        scanThread.start();
    }

    private void showDiscovered() {
        if (discovered.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("未发现设备")
                .setMessage("请确认 Windows EXE 或 Pad 接收端正在运行且处于同一 Wi‑Fi。也可以使用'手动添加 IP'。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        
        String[] labels = new String[discovered.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = discovered.get(i).label();
        }
        new AlertDialog.Builder(this)
            .setTitle("选择接收端")
            .setItems(labels, (d, which) -> askPairCode(discovered.get(which)))
            .show();
    }

    private void askPairCode(TargetStore.Target base) {
        EditText code = new EditText(this);
        code.setHint("接收端显示的 6 位配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        
        new AlertDialog.Builder(this)
            .setTitle("配对 " + base.name)
            .setView(code)
            .setPositiveButton("保存", (d, w) -> {
                String c = code.getText().toString().trim();
                if (c.length() != 6) {
                    Toast.makeText(this, "配对码应为 6 位", Toast.LENGTH_LONG).show();
                    return;
                }
                List<TargetStore.Target> ts = TargetStore.load(this);
                ts.add(new TargetStore.Target(base.name, base.host, base.port, c));
                TargetStore.save(this, ts);
                renderTargets();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void manualAdd() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = 24;
        box.setPadding(p, p, p, p);
        
        EditText name = new EditText(this);
        name.setHint("名称，例如 Gaming-PC");
        EditText host = new EditText(this);
        host.setHint("IP，例如 192.168.1.20");
        EditText code = new EditText(this);
        code.setHint("6 位配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        
        box.addView(name);
        box.addView(host);
        box.addView(code);
        
        new AlertDialog.Builder(this)
            .setTitle("手动添加接收端")
            .setView(box)
            .setPositiveButton("保存", (d, w) -> {
                if (host.getText().toString().trim().isEmpty() || 
                    code.getText().toString().trim().length() != 6) {
                    Toast.makeText(this, "请填写 IP 和 6 位配对码", Toast.LENGTH_LONG).show();
                    return;
                }
                List<TargetStore.Target> ts = TargetStore.load(this);
                String targetName = name.getText().toString().trim().isEmpty() ? 
                    "Manual" : name.getText().toString().trim();
                ts.add(new TargetStore.Target(targetName, host.getText().toString().trim(), 58123, 
                    code.getText().toString().trim()));
                TargetStore.save(this, ts);
                renderTargets();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void sendTest() {
        List<TargetStore.Target> ts = TargetStore.load(this);
        if (ts.isEmpty() && !CloudConfigStore.isConfigured(CloudConfigStore.load(this))) {
            Toast.makeText(this, "请先添加 LAN 接收端或完成云端配对", Toast.LENGTH_LONG).show();
            return;
        }
        Forwarder.forwardAsync(this, UUID.randomUUID().toString(), "MsgDock", 
            "测试消息：局域网/云端短信转发已连通。验证码 123456", System.currentTimeMillis(), -1);
        Toast.makeText(this, "已发送测试消息（LAN 与云端共用同一 ID）", Toast.LENGTH_SHORT).show();
    }

    private void requestBatteryWhitelist() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, 
                Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i != null) startActivity(i);
            else Toast.makeText(this, "未检测到 Shizuku", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开 Shizuku", Toast.LENGTH_LONG).show();
        }
    }

    private void copyAdbCommands() {
        String pkg = getPackageName();
        String cmd = "adb shell pm grant " + pkg + " android.permission.RECEIVE_SMS\n" +
            "adb shell pm grant " + pkg + " android.permission.NEARBY_WIFI_DEVICES\n" +
            "adb shell cmd appops set " + pkg + " RUN_IN_BACKGROUND allow\n" +
            "adb shell cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow\n" +
            "adb shell dumpsys deviceidle whitelist +" + pkg;
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("ADB/Shizuku", cmd));
        
        new AlertDialog.Builder(this)
            .setTitle("已复制")
            .setMessage(cmd + "\n\n可在电脑 ADB 中执行；使用 Shizuku 时，可在 rish/支持 Shizuku 的终端中去掉每行开头的 `adb shell ` 后执行。" +
                "注意：这只能补权限/后台策略，无法绕过 HyperOS 在短信广播之前的系统级过滤。")
            .setPositiveButton("确定", null)
            .show();
    }

    private boolean activityAlive() {
        return !isFinishing() && (Build.VERSION.SDK_INT < 17 || !isDestroyed());
    }
}
