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
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    // UI references
    private EditText relayUrlEdit;
    private TextView cloudStatusText, machineCodeText, backupStatusText;
    private TextView smsPermissionText, nearbyPermissionText, receiverStatusText;
    private TextView accountStatusText;
    private EditText accountUsernameEdit, accountEmailEdit, accountPasswordEdit;
    private LinearLayout cloudLinksContainer, targetsContainer;
    private Button cloudPairSenderBtn, cloudPairReceiverBtn;
    private View nearbyPermissionRow;

    private final List<TargetStore.Target> discovered = Collections.synchronizedList(new ArrayList<>());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        
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
        cloudLinksContainer = findViewById(R.id.container_cloud_links);
        targetsContainer = findViewById(R.id.container_targets);
        cloudPairSenderBtn = findViewById(R.id.btn_cloud_pair_sender);
        cloudPairReceiverBtn = findViewById(R.id.btn_cloud_pair_receiver);
        nearbyPermissionRow = findViewById(R.id.row_nearby_permission);
    }

    private void setupListeners() {
        findViewById(R.id.btn_save_relay).setOnClickListener(v -> saveRelayUrl());
        cloudPairSenderBtn.setOnClickListener(v -> cloudPairSender());
        cloudPairReceiverBtn.setOnClickListener(v -> cloudPairReceiver());
        
        findViewById(R.id.btn_copy_machine_code).setOnClickListener(v -> copyMachineCode());
        findViewById(R.id.btn_sync_recover).setOnClickListener(v -> syncOrRecover());
        findViewById(R.id.btn_delete_device).setOnClickListener(v -> confirmDeleteDevice());
        
        findViewById(R.id.btn_request_sms).setOnClickListener(v -> 
            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 10));
        findViewById(R.id.btn_request_nearby).setOnClickListener(v -> 
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 12));
        
        findViewById(R.id.btn_scan_lan).setOnClickListener(v -> scanLan());
        findViewById(R.id.btn_manual_add).setOnClickListener(v -> manualAdd());
        findViewById(R.id.btn_send_test).setOnClickListener(v -> sendTest());
        
        findViewById(R.id.btn_start_receiver).setOnClickListener(v -> startReceiver());
        findViewById(R.id.btn_stop_receiver).setOnClickListener(v -> stopReceiver());
        
        findViewById(R.id.btn_battery_optimize).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.btn_app_settings).setOnClickListener(v -> 
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, 
                Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btn_open_shizuku).setOnClickListener(v -> openShizuku());
        findViewById(R.id.btn_copy_adb).setOnClickListener(v -> copyAdbCommands());

        findViewById(R.id.btn_account_login).setOnClickListener(v -> accountLogin());
        findViewById(R.id.btn_account_register).setOnClickListener(v -> accountRegister());
        findViewById(R.id.btn_account_logout).setOnClickListener(v -> accountLogout());
    }

    private void render() {
        // Cloud relay
        CloudConfigStore.Config cloud = CloudConfigStore.load(this);
        relayUrlEdit.setText(cloud.relayUrl);
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
        accountStatusText.setText(AccountApi.statusText(this));
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

    private void saveRelayUrl() {
        try {
            CloudRelay.saveRelayUrl(this, relayUrlEdit.getText().toString());
            Toast.makeText(this, "Relay 地址已保存", Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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
        String username = accountUsernameEdit.getText().toString().trim();
        String email = accountEmailEdit.getText().toString().trim();
        String password = accountPasswordEdit.getText().toString();
        String identifier = username.isEmpty() ? email : username;
        Toast.makeText(this, "正在登录 MsgDock…", Toast.LENGTH_SHORT).show();
        AccountApi.login(this, identifier, password, (success, message) -> {
            if (!activityAlive()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private void accountRegister() {
        String username = accountUsernameEdit.getText().toString().trim();
        String email = accountEmailEdit.getText().toString().trim();
        String password = accountPasswordEdit.getText().toString();
        Toast.makeText(this, "正在注册 MsgDock…", Toast.LENGTH_SHORT).show();
        AccountApi.register(this, username, email, password, (success, message) -> {
            if (!activityAlive()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private void accountLogout() {
        AccountApi.logout(this, (success, message) -> {
            if (!activityAlive()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
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
        return "状态：" + (on ? "✓ 运行中" : "未启动") +
            "\n地址：http://" + ReceiverService.localIpv4(this) + ":58123" +
            "\nLAN 配对码：" + TargetStore.ensurePairCode(this) +
            "\n云接收链路：" + CloudConfigStore.receiverLinks(this).size() + " 条" +
            (pending.isEmpty() ? "" : "\n云接收配对码：" + pending);
    }

    private void startReceiver() {
        if (Build.VERSION.SDK_INT >= 33 && 
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
        TargetStore.prefs(this).edit().putBoolean(ReceiverService.PREF_RECEIVER_ENABLED, true).commit();
        ReceiverService.ensureStarted(this);
        Toast.makeText(this, "接收端已启动", Toast.LENGTH_SHORT).show();
        render();
    }

    private void stopReceiver() {
        TargetStore.prefs(this).edit().putBoolean(ReceiverService.PREF_RECEIVER_ENABLED, false).commit();
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
        if (Build.VERSION.SDK_INT >= 33 && 
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 12);
        }
        Toast.makeText(this, "正在扫描约 5 秒…", Toast.LENGTH_SHORT).show();
        discovered.clear();
        
        Executors.newSingleThreadExecutor().execute(() -> {
            long end = System.currentTimeMillis() + 5200;
            Set<String> seen = new HashSet<>();
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(ReceiverService.DISCOVERY_PORT));
                LanNet.bindWifi(this, ds);
                ds.setSoTimeout(600);
                byte[] buf = new byte[1024];
                
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String s = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                        String[] f = s.split("\\|", 4);
                        if (f.length == 4 && "XGY_SMS_V1".equals(f[0])) {
                            String key = f[2] + ":" + f[3];
                            if (seen.add(key)) {
                                discovered.add(new TargetStore.Target(f[1], f[2], Integer.parseInt(f[3]), ""));
                            }
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception ignored) {}
            runOnUiThread(this::showDiscovered);
        });
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
