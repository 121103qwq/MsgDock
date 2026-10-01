package com.xgy.lansms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class BackgroundGuideActivity extends Activity {
    private int selected;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_background_guide);
        WindowLayout.apply(this);
        int detected = BackgroundGuideContent.indexFor(Build.MANUFACTURER, Build.BRAND);
        selected = state == null ? detected : state.getInt("guide_index", detected);
        if (selected < 0 || selected >= BackgroundGuideContent.GUIDES.length) selected = detected;
        ((TextView) findViewById(R.id.guide_device)).setText("当前设备：" + Build.MANUFACTURER + " / " + Build.MODEL
            + " · Android " + Build.VERSION.RELEASE + "\n按品牌推荐；刷机或识别不准时可手动切换。");
        findViewById(R.id.guide_back).setOnClickListener(v -> finish());
        findViewById(R.id.guide_brand).setOnClickListener(v -> chooseBrand());
        findViewById(R.id.guide_app_settings).setOnClickListener(v -> open(new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.guide_battery_settings).setOnClickListener(v -> open(new Intent(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        findViewById(R.id.guide_sources).setOnClickListener(v -> showSources());
        renderGuide();
    }

    @Override protected void onResume() {
        super.onResume();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        String status = power == null ? "系统电池优化：无法读取状态"
            : power.isIgnoringBatteryOptimizations(getPackageName())
                ? "系统电池优化：已豁免 MsgDock"
                : "系统电池优化：尚未豁免 MsgDock";
        ((TextView) findViewById(R.id.guide_battery_status)).setText(status
            + "\n此状态只反映 Android 标准电池优化，厂商的自启动和后台小锁仍需手动确认。");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("guide_index", selected);
        super.onSaveInstanceState(state);
    }

    private void chooseBrand() {
        String[] labels = new String[BackgroundGuideContent.GUIDES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = BackgroundGuideContent.GUIDES[i].label;
        new AlertDialog.Builder(this).setTitle("选择手机品牌")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                selected = which;
                renderGuide();
                ((ScrollView) findViewById(R.id.guide_scroll)).smoothScrollTo(0, 0);
                dialog.dismiss();
            }).setNegativeButton("取消", null).show();
    }

    private void renderGuide() {
        BackgroundGuideContent.Guide guide = BackgroundGuideContent.GUIDES[selected];
        ((Button) findViewById(R.id.guide_brand)).setText("品牌：" + guide.label + "  ▾");
        ((TextView) findViewById(R.id.guide_battery)).setText(guide.battery);
        ((TextView) findViewById(R.id.guide_recents)).setText(guide.recents);
        ((TextView) findViewById(R.id.guide_autostart)).setText(guide.autostart);
        ((TextView) findViewById(R.id.guide_note)).setText(guide.note);
    }

    private void showSources() {
        String[][] sources = BackgroundGuideContent.GUIDES[selected].sources;
        String[] labels = new String[sources.length];
        for (int i = 0; i < labels.length; i++) labels[i] = sources[i][0];
        new AlertDialog.Builder(this).setTitle("官方参考资料 · 需联网打开")
            .setItems(labels, (dialog, which) -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(sources[which][1]))))
            .setNegativeButton("取消", null).show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "此设备无法直接打开，请按教程在系统设置中搜索；官方资料可稍后用浏览器查看。", Toast.LENGTH_LONG).show();
        }
    }
}
