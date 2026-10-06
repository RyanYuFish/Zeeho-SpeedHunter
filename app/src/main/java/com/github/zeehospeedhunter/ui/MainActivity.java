package com.github.zeehospeedhunter.ui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
    private MaterialSwitch swRideFill;

    private TextView tvStatusDetail;
    private TextView tvModeChip;
    private TextView tvHudState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdge();
        setContentView(R.layout.activity_main);
        BackGestureCompat.install(this);
        applyInsets();

        prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);

        TextView version = findViewById(R.id.tv_version);
        version.setText("v" + versionName() + " · com.cfmoto（LSPosed 作用域需勾选）");

        swWeb = findViewById(R.id.sw_web);
        swG1 = findViewById(R.id.sw_g1);
        swG2 = findViewById(R.id.sw_g2);
        swView = findViewById(R.id.sw_view);
        swRideFill = findViewById(R.id.sw_ride_fill);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvModeChip = findViewById(R.id.tv_mode_chip);
        tvHudState = findViewById(R.id.tv_hud_state);

        swWeb.setChecked(prefs.getBoolean(Keys.KEY_WEB_LAYER, true));
        swG1.setChecked(prefs.getBoolean(Keys.KEY_GATE_ANALYSE, true));
        swG2.setChecked(prefs.getBoolean(Keys.KEY_GATE_EVENT, true));
        swView.setChecked(prefs.getBoolean(Keys.KEY_VIEW_LAYER, false));
        swRideFill.setChecked(prefs.getBoolean(Keys.KEY_RIDE_FILL, true));

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
        swRideFill.setOnCheckedChangeListener(listener);

        // 自制固件 OTA：跳「最后一公里」操作台（P2P 建连 / 推包 / 触发 / 看日志）
        findViewById(R.id.btn_ota_flash).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            MainActivity.this, OtaActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开 OTA 操作台：" + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        // 压弯标定：8 个阈值实时可调，每项带「调它会发生什么」说明
        findViewById(R.id.btn_tune).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            MainActivity.this, TuneActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开标定台：" + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        // 仪表投屏：独立入口，绕过官方 App 手动操作（解析二维码 → 连 AP → 拉起投屏）
        findViewById(R.id.btn_mirror).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            MainActivity.this, MirrorActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开投屏页：" + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        updateStatus();
    }

    // ==================== 手机端日志 ====================

    /**
     * 日志现在<b>只在 OTA 操作台页内</b>显示（{@code activity_ota.xml} 的日志区 + Tab 切两份）。
     *
     * <p>2026-10-04 之前这里有个「悬浮窗权限」入口，为的是操作台挂日志悬浮窗。
     * 实测两个问题：悬浮窗会盖在 ZEEHO App 上（而这一步本来就要来回切 App 看状态），
     * 而且平白多要一个 {@code SYSTEM_ALERT_WINDOW} 授权页。改成页内日志区后都不需要了。</p>
     *
     * <p>日志本身一直照常落文件（{@code com.cfmoto/files/zeeho_hook.log}）＋ logcat tag
     * {@code ZeehoHook}，要完整历史仍可用 adb 读文件。</p>
     */
    private void updateHudState() {
        if (tvHudState != null) {
            tvHudState.setText("日志在「自制固件 OTA 操作台」页内显示（Tab 可切「操作日志 / 车机日志」），"
                    + "不再用悬浮窗；同时落 zeeho_hook.log，adb 可直接读");
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ==================== edge-to-edge ====================

    /**
     * 沉浸式 + 修状态栏图标明暗。
     *
     * <p>已抽到 {@link EdgeToEdge} 供三个页面共用 —— 原来只有这里调，
     * 另两页（操作台 / 标定台）浅色底上是白图标，等于白底白字看不见。</p>
     */
    private void setupEdgeToEdge() {
        EdgeToEdge.setup(this, findViewById(android.R.id.content));
    }

    /** XML 里的 padding 是基准值，系统栏 insets 叠加在上面。 */
    private void applyInsets() {
        final View root = findViewById(R.id.root_scroll);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
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
                    .putBoolean(Keys.KEY_RIDE_FILL, swRideFill.isChecked())
                    .putBoolean(Keys.KEY_RIDE_FULLSCAN, true)
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
            intent.putExtra(Keys.KEY_RIDE_FILL, swRideFill.isChecked());
            intent.putExtra(Keys.KEY_RIDE_FULLSCAN, true);
            intent.setPackage(Keys.TARGET_PKG);   // ★ 见 Keys#TARGET_PKG：不加这行 Android 8+ 会拦掉
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
