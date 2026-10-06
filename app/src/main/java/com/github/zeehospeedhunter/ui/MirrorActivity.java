package com.github.zeehospeedhunter.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.ota.CarWifi;
import com.github.zeehospeedhunter.ota.EcHandshakeProbe;

import java.util.List;

/**
 * 仪表投屏 —— 模块<b>单独入口</b>，绕过官方 App 手动操作。
 *
 * <h3>做到的事 / 做不到的事</h3>
 * <ul>
 *   <li><b>本页做</b>：解析投屏二维码 URL（拿到车机 AP 的 SSID/密码）→ 用 {@link CarWifi}
 *       把手机加入车机 AP（192.168.0.x）→ <b>用 {@link EcHandshakeProbe} 探测车机 EC 通道</b>
 *       （同构 {@code sendClientInfo} 帧，只测可达/回包）→ 再经 {@code Keys.ACTION_MIRROR_START}
 *       广播，<b>由 hook 在 com.cfmoto 进程内</b>拉起官方投屏入口。</li>
 *   <li><b>不重写</b>：镜像本身的私有协议是 Carbit ECSDK（手机侧被爱加密整包加密，
 *       cmdType 数值表与握手时序静态不可得），无法在本模块重写；所以「推镜像帧」仍由官方
 *       EC 栈完成。本页的价值是把<b>网络层</b>、<b>二维码</b>、<b>通道自检</b>从官方 App 的
 *       手工流程里剥离，做到「不打开官方 App 也能投屏」，并用探针把「投得出来吗」
 *       从猜测变成事实。完整分析见 {@code docs/49}。</li>
 * </ul>
 *
 * <p>日志在固定 280dp 窗口内滚动（同 OtaActivity 套路）。</p>
 */
public final class MirrorActivity extends androidx.appcompat.app.AppCompatActivity {

    private static final int LOG_MAX_LINES = 400;

    private TextView tvLog;
    private TextView tvStatus;
    private TextView tvScan;
    private EditText etUrl;
    private EditText etSsid;
    private EditText etPw;
    private View btnParse, btnSave, btnConnect, btnStart, btnStop, btnEcProbe;

    private Handler main;
    private final StringBuilder log = new StringBuilder();

    private final ActivityResultLauncher<String> reqNearbyWifi =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    this::onNearbyWifiPermission);
    private volatile Runnable afterWifiPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mirror);
        BackGestureCompat.install(this);
        EdgeToEdge.setup(this, findViewById(R.id.root_mirror));
        main = new Handler(Looper.getMainLooper());

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);
        tvScan = findViewById(R.id.tv_wifi_scan);
        etUrl = findViewById(R.id.et_mirror_url);
        etSsid = findViewById(R.id.et_car_ssid);
        etPw = findViewById(R.id.et_car_pw);
        btnParse = findViewById(R.id.btn_parse_url);
        btnSave = findViewById(R.id.btn_save_wifi);
        btnConnect = findViewById(R.id.btn_connect_car);
        btnStart = findViewById(R.id.btn_start_mirror);
        btnStop = findViewById(R.id.btn_stop_mirror);
        btnEcProbe = findViewById(R.id.btn_ec_probe);

        android.content.SharedPreferences prefs = getSharedPreferences("mirror_car_wifi", MODE_PRIVATE);
        etSsid.setText(prefs.getString("car_ssid", CarWifi.DEFAULT_SSID));
        etPw.setText(prefs.getString("car_pw", ""));

        setupLogScrolling(tvLog);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());

        btnParse.setOnClickListener(v -> parseUrl());
        btnSave.setOnClickListener(v -> saveWifi());
        btnConnect.setOnClickListener(v -> connectCar());
        btnStart.setOnClickListener(v -> startMirror());
        btnStop.setOnClickListener(v -> stopMirror());
        btnEcProbe.setOnClickListener(v -> runEcProbe());
    }

    // ==================== 二维码 URL 解析 ====================

    /**
     * 投屏二维码规律（固件实证，见 docs/39 §16.2）：
     * 车机 {@code libeasyconnectmodule.so::openWifiEcService_generateQRCodeUrl()} 生成，
     * 形如 {@code https://zhbackend.zeehoev.com/app_download/?...&action=9
     * &ssid=ZEEHO-204dfd&pwd=071284a88d&auth=wpa2-psk&mac=...&name=ZEEHO-204dfd}
     * ⇒ action=9 = AP_MODE：车机开热点，手机来连。这里把 ssid/pwd 抽出来填进输入框。
     */
    private void parseUrl() {
        String raw = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            append("请先粘贴投屏二维码 URL");
            return;
        }
        try {
            Uri u = Uri.parse(raw);
            String ssid = u.getQueryParameter("ssid");
            String pwd = u.getQueryParameter("pwd");
            String action = u.getQueryParameter("action");
            if (TextUtils.isEmpty(ssid)) {
                append("⚠ URL 里没找到 ssid= 参数（不是投屏二维码？）");
                return;
            }
            etSsid.setText(ssid);
            etPw.setText(pwd == null ? "" : pwd);
            append("✔ 已解析：SSID=" + ssid
                    + (TextUtils.isEmpty(pwd) ? "（开放网络）" : "（密码已填）")
                    + (action != null ? "  action=" + action : ""));
            if (!"9".equals(action)) {
                append("⚠ action=" + action + "（非 9）；投屏一般是 action=9(AP_MODE)，确认这是投屏二维码");
            }
        } catch (Throwable t) {
            append("⚠ 解析失败：" + t);
        }
    }

    private void saveWifi() {
        String ssid = etSsid.getText().toString().trim();
        String pw = etPw.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            append("SSID 不能为空");
            return;
        }
        getSharedPreferences("mirror_car_wifi", MODE_PRIVATE).edit()
                .putString("car_ssid", ssid).putString("car_pw", pw).apply();
        append("已保存：SSID=" + ssid + (TextUtils.isEmpty(pw) ? "（开放网络）" : "（密码已存）"));
    }

    // ==================== 加入车机 AP ====================

    private void connectCar() {
        ensureWifiPermission(() -> {
            append("=== 连接车机热点 ===");
            setBusy(true);
            tvStatus.setText("连接车机热点…");
            doConnect();
        });
    }

    private void doConnect() {
        List<String> aps = CarWifi.scanCarAp(this);
        String ssid = etSsid.getText().toString().trim();
        if (aps.isEmpty()) {
            tvScan.setText("未扫到 ZEEHO-* AP（确认车机投屏已开、手机其它热点已关）");
            append("   附近未扫到 ZEEHO-* AP");
        } else {
            tvScan.setText("附近车机 AP：" + TextUtils.join("  ", aps));
            append("   附近车机 AP：" + TextUtils.join(", ", aps));
            if (!aps.contains(ssid)) {
                ssid = aps.get(0);
                etSsid.setText(ssid);
                append("   用扫到的 " + ssid);
            }
        }
        String pw = etPw.getText().toString();
        CarWifi.connect(this, ssid, pw, new CarWifi.ConnectResult() {
            @Override
            public void onResult(boolean ok, String msg) {
                append("   " + msg);
                main.post(() -> {
                    if (ok) {
                        tvStatus.setText("已连车机热点 · 等几秒拿 IP 后点「开始投屏」");
                        append("✔ 连上车机 AP；稍候本机应拿到 192.168.0.x");
                    } else {
                        setBusy(false);
                        tvStatus.setText("连接车机热点失败");
                    }
                });
            }
        });
    }

    // ==================== 启动投屏（交给 hook） ====================

    /**
     * 经广播把「启动投屏」交给 hook（com.cfmoto 内），由 {@code MirrorTrigger} 拉起官方投屏入口。
     * 本进程（模块 UI）无法启动 app 内未 exported 的投屏 Activity，必须走这条同 UID 路径。
     */
    private void startMirror() {
        String ssid = etSsid.getText().toString().trim();
        String pw = etPw.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            append("SSID 为空，先填或解析二维码");
            return;
        }
        append("=== 开始投屏 ===");
        append("发 ACTION_MIRROR_START 给 com.cfmoto 内 hook（ssid=" + ssid + "）…");
        tvStatus.setText("拉起投屏…");
        try {
            Intent it = new Intent(Keys.ACTION_MIRROR_START);
            it.setPackage(Keys.TARGET_PKG);
            it.putExtra(Keys.EXTRA_MIRROR_SSID, ssid);
            it.putExtra(Keys.EXTRA_MIRROR_PWD, pw);
            sendBroadcast(it);
            append("✔ 已发送；hook 会启动官方投屏入口，请在车机上看是否进入镜像");
        } catch (Throwable t) {
            append("⚠ 发送失败：" + t + "（确认 ZEEHO App 在 LSPosed 作用域且已运行）");
            tvStatus.setText("启动投屏失败");
        }
    }

    /**
     * EC 握手探针：不推镜像，只用与官方同构的 {@code sendClientInfo} 帧敲车机 PXC 口，
     * 把「通道可达 / 是否回包」变成事实（而不是猜 Activity 名）。
     * 分析见 {@code docs/49}。
     */
    private void runEcProbe() {
        append("=== EC 握手探针 ===");
        tvStatus.setText("探测 EC 通道…");
        EcHandshakeProbe.probe(new EcHandshakeProbe.Listener() {
            @Override
            public void onStage(String s) {
                append(s);
            }

            @Override
            public void onResult(List<EcHandshakeProbe.ProbeResult> results) {
                final int hits = countHits(results);
                append("—— 探测结束：命中 " + hits + " 个回包端口 ——");
                for (EcHandshakeProbe.ProbeResult r : results) {
                    if (r.connected) {
                        append(String.format("   %s:%d  连通%s", r.host, r.port,
                                r.replied ? "，★回包 " + r.replyHex : "，无回包"));
                    }
                }
                main.post(() -> {
                    if (hits > 0) {
                        tvStatus.setText("EC 通道可达（" + hits + " 个端口回包）· 可点「开始投屏」");
                    } else {
                        tvStatus.setText("未扫到回包的 EC 端口 · 确认已连热点且仪表投屏已开");
                    }
                });
            }
        });
    }

    private int countHits(List<EcHandshakeProbe.ProbeResult> results) {
        int n = 0;
        for (EcHandshakeProbe.ProbeResult r : results) {
            if (r.ok()) {
                n++;
            }
        }
        return n;
    }

    private void stopMirror() {
        append("=== 断开投屏 ===");
        try {
            Intent it = new Intent(Keys.ACTION_MIRROR_STOP);
            it.setPackage(Keys.TARGET_PKG);
            sendBroadcast(it);
        } catch (Throwable ignored) {
        }
        CarWifi.release(this);
        append("✔ 已释放车机 AP 连接请求");
        tvStatus.setText("已断开");
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        btnStart.setEnabled(!busy);
        btnConnect.setEnabled(!busy);
    }

    // ==================== 权限（连车机 AP 只需附近 Wi-Fi） ====================

    private void ensureWifiPermission(Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                afterWifiPermission = action;
                reqNearbyWifi.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES);
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                afterWifiPermission = action;
                reqNearbyWifi.launch(android.Manifest.permission.ACCESS_FINE_LOCATION);
                return;
            }
        }
        action.run();
    }

    private void onNearbyWifiPermission(boolean granted) {
        Runnable a = afterWifiPermission;
        afterWifiPermission = null;
        if (a == null) {
            return;
        }
        if (granted) {
            a.run();
        } else {
            append("⚠ 未授予 Wi-Fi 权限，无法自动连车机 AP；请去系统设置授权，或手动在系统 Wi-Fi 里连 ZEEHO-xxxx");
            tvStatus.setText("缺少 Wi-Fi 权限");
            setBusy(false);
        }
    }

    // ==================== 日志 ====================

    private void clearLog() {
        log.setLength(0);
        tvLog.setText("（日志已清空）");
    }

    private void setupLogScrolling(TextView tv) {
        tv.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tv.setVerticalScrollBarEnabled(true);
        tv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });
    }

    private void append(final String s) {
        HookLog.log(HookLog.MIRROR + " UI " + s);
        main.post(() -> {
            log.append(s).append('\n');
            if (log.length() > LOG_MAX_LINES * 100) {
                int nl = log.indexOf("\n");
                if (nl >= 0) {
                    log.delete(0, nl + 1);
                }
            }
            tvLog.setText(log.toString());
            tvLog.post(new Runnable() {
                @Override
                public void run() {
                    android.text.Layout l = tvLog.getLayout();
                    if (l == null) {
                        return;
                    }
                    int bottom = l.getHeight() - tvLog.getHeight();
                    if (bottom < 0) {
                        bottom = 0;
                    }
                    tvLog.scrollTo(0, bottom);
                }
            });
        });
    }
}
