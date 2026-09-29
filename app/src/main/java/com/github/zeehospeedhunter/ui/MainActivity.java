package com.github.zeehospeedhunter.ui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.Keys;

/**
 * 模块设置界面（Material You）。
 *
 * <p>展示当前对 ZEEHO App 的挂载方式（网络层 / 视图层）并允许开关切换。
 * 开关写入本模块的 SharedPreferences，hook 进程经
 * {@code ConfigProvider}（导出的 ContentProvider）读取 —— 改完冷重启 ZEEHO App 生效。</p>
 */
public final class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private MaterialSwitch swWeb;
    private MaterialSwitch swG1;
    private MaterialSwitch swG2;
    private MaterialSwitch swView;

    private TextView tvStatusDetail;
    private TextView tvModeChip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdge();
        setContentView(R.layout.activity_main);
        applyInsets();

        prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);

        TextView version = findViewById(R.id.tv_version);
        version.setText("v" + versionName() + " · com.cfmoto（LSPosed 作用域需勾选）");

        swWeb = findViewById(R.id.sw_web);
        swG1 = findViewById(R.id.sw_g1);
        swG2 = findViewById(R.id.sw_g2);
        swView = findViewById(R.id.sw_view);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvModeChip = findViewById(R.id.tv_mode_chip);

        swWeb.setChecked(prefs.getBoolean(Keys.KEY_WEB_LAYER, true));
        swG1.setChecked(prefs.getBoolean(Keys.KEY_GATE_ANALYSE, true));
        swG2.setChecked(prefs.getBoolean(Keys.KEY_GATE_EVENT, true));
        swView.setChecked(prefs.getBoolean(Keys.KEY_VIEW_LAYER, false));

        // 首次打开就把默认值落盘 —— 否则 prefs 文件不存在，
        // hook 进程的 XSharedPreferences 兜底通道拿不到任何键
        if (!prefs.contains(Keys.KEY_WEB_LAYER)) {
            persist();
        }

        SwitchListener listener = new SwitchListener();
        swWeb.setOnCheckedChangeListener(listener);
        swG1.setOnCheckedChangeListener(listener);
        swG2.setOnCheckedChangeListener(listener);
        swView.setOnCheckedChangeListener(listener);

        updateStatus();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ==================== edge-to-edge ====================

    /** 内容画到状态栏 / 导航栏底下，栏本身透明（SukiSU 式沉浸）。 */
    private void setupEdgeToEdge() {
        android.view.Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        // 按当前日/夜模式决定状态栏图标颜色（M3 DayNight 跟随系统）
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightStatusBars(nightMode != Configuration.UI_MODE_NIGHT_YES);
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(nightMode != Configuration.UI_MODE_NIGHT_YES);
    }

    /** XML 里的 padding 是基准值，系统栏 insets 叠加在上面。 */
    private void applyInsets() {
        final View root = findViewById(R.id.root_scroll);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom);
            return windowInsets;
        });
    }

    private void updateStatus() {
        boolean web = swWeb.isChecked();
        boolean view = swView.isChecked();

        String detail;
        String chip;
        if (web && view) {
            detail = "网络层 + 视图层兜底（叠加）——能力位改写 + 控件强制点亮";
            chip = "WEB+VIEW";
        } else if (web) {
            detail = "网络层（推荐）——改写车型能力位，App 自己显示骑行分析与四项指标";
            chip = "WEB";
        } else if (view) {
            detail = "视图层兜底——直接把控件沿祖先链点亮（旧方案，App 换判定源时才用）";
            chip = "VIEW";
        } else {
            detail = "未挂载——不修改 ZEEHO App";
            chip = "OFF";
        }
        tvStatusDetail.setText(detail);
        tvModeChip.setText(chip);

        // chip 底色跟状态走：激活用 colorPrimary，关闭用 onSurfaceVariant
        int color = ContextCompat.getColor(this,
                (web || view) ? R.color.chip_active : R.color.chip_off);
        tvModeChip.getBackground().mutate().setTint(color);
        tvModeChip.setTextColor(Color.WHITE);

        // G1/G2 依赖网络层，关掉网络层时置灰
        boolean gatesEnabled = web;
        swG1.setEnabled(gatesEnabled);
        swG2.setEnabled(gatesEnabled);
        float alpha = gatesEnabled ? 1f : 0.45f;
        swG1.setAlpha(alpha);
        swG2.setAlpha(alpha);
    }

    private void persist() {
        try {
            prefs.edit()
                    .putBoolean(Keys.KEY_WEB_LAYER, swWeb.isChecked())
                    .putBoolean(Keys.KEY_GATE_ANALYSE, swG1.isChecked())
                    .putBoolean(Keys.KEY_GATE_EVENT, swG2.isChecked())
                    .putBoolean(Keys.KEY_VIEW_LAYER, swView.isChecked())
                    .commit();
        } catch (Throwable ignored) {
            // prefs 可能被 LSPosed 重定向或只读 —— 真正的通道是下面的广播
        }

        // 主通道：显式广播给 com.cfmoto 里 hook 注册的动态接收器
        // （ZEEHO App 运行中 → 内存即时生效 + hook 自己落盘；冷重启后从落盘文件恢复）
        try {
            android.content.Intent intent = new android.content.Intent(Keys.ACTION_CONFIG);
            intent.setPackage("com.cfmoto");
            intent.putExtra(Keys.KEY_WEB_LAYER, swWeb.isChecked());
            intent.putExtra(Keys.KEY_GATE_ANALYSE, swG1.isChecked());
            intent.putExtra(Keys.KEY_GATE_EVENT, swG2.isChecked());
            intent.putExtra(Keys.KEY_VIEW_LAYER, swView.isChecked());
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        makePrefsWorldReadable();
    }

    /**
     * XSharedPreferences 兜底通道要求配置文件可被其他进程读。
     * 主通道是 ContentProvider，不依赖这里；这一步是尽力而为（失败不影响主链路）。
     */
    private void makePrefsWorldReadable() {
        try {
            File dir = new File(getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.setReadable(true, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private final class SwitchListener implements MaterialSwitch.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton button, boolean isChecked) {
            // 关掉网络层时保持 G1/G2 视觉跟随（值保留，重新打开即恢复）
            persist();
            updateStatus();
        }
    }
}
