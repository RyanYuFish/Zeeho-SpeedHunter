package com.github.zeehospeedhunter.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.ota.CarWifi;
import com.github.zeehospeedhunter.ota.OtaPushService;
import com.github.zeehospeedhunter.ota.OtaSocketPusher;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.List;

/**
 * 「最后一公里」操作台 —— <b>只保留已经跑通的 {@code socket_l} OTA 通道</b>。
 *
 * <h3>为什么只剩这一条路</h3>
 * <p>车机 OTA 的官方通道是 {@code 192.168.0.1:10950} 上的私有 TCP 帧协议
 * （见 {@code docs/42} / {@code docs/44} / {@code docs/47}）。P2P + ADB 推包、
 * EasyConnect 投屏触发这些路都已经被证伪或卡死在厂商鉴权缺口上，
 * 而 {@code socket_l} 这条路是<b>固件交叉验证过</b>的：帧头字节级一致、
 * {@code otaStart} 会 {@code fopen64} 并打开写块门控、{@code fileSize} 决定收尾。
 * 所以操作台只留它，别的按钮全部砍掉，省得现场在死路上反复试。</p>
 *
 * <h3>本页做的事</h3>
 * <pre>
 *   探测车辆（网段 / 10950 通不通 / 车型 + 版本 + VIN，车型直接用于推送）
 *   → 选包（系统文件选择器，免任何权限） 或 自动定位 Download/ 下的包
 *   → 探测 192.168.0.1:10950（只发版本协商，不触发 OTA）
 *   → 前台服务推送（常驻通知 + 进度条，断电/被杀都不会静默中断）
 *   → 车机校验请求 0x10000004 由手机自动回 ack 0x80000004
 * </pre>
 *
 * <p>日志在固定 280dp 的窗口内滚动（不撑长整页），推送进度由
 * {@link LinearProgressIndicator} 实时显示。</p>
 */
public final class OtaActivity extends androidx.appcompat.app.AppCompatActivity
        implements OtaSocketPusher.Callback, OtaPushService.Progress {

    private static final int LOG_MAX_LINES = 400;

    private TextView tvLog;
    private TextView tvPkg;
    private TextView tvVehicle;
    private TextView tvStatus;
    private LinearProgressIndicator pb;
    private TextView tvProgress;
    private View btnPick, btnProbe, btnPush, btnVehicle, btnConnect, btnOneStop, btnSaveWifi;
    private com.google.android.material.button.MaterialButton btnGrant;
    private EditText etSsid, etPw;
    private TextView tvScan;

    private SharedPreferences prefs;
    private Handler main;
    private final StringBuilder log = new StringBuilder();
    private volatile File picked;

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPicked);

    /** Android 13+ 连指定 Wi-Fi 需要 NEARBY_WIFI_DEVICES 运行时权限。 */
    private final ActivityResultLauncher<String> reqNearbyWifi =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    this::onNearbyWifiPermission);

    /** 权限到手后要执行的动作（连车机 Wi-Fi / 扫 AP）。 */
    private volatile Runnable afterWifiPermission;

    /**
     * 走系统设置页授予的「特殊权限」回程后要继续的动作
     * （WRITE_SETTINGS / MANAGE_EXTERNAL_STORAGE 都靠离开 App 去设置页点，
     * onResume 里据此续跑）。
     */
    private volatile Runnable pendingAfterPerms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ota);
        BackGestureCompat.install(this);
        // ★ 沉浸式 + 状态栏图标明暗：不调的话浅色底上是白图标，等于白底白字
        EdgeToEdge.setup(this, findViewById(R.id.root_ota));
        main = new Handler(Looper.getMainLooper());

        tvLog = findViewById(R.id.tv_log);
        tvPkg = findViewById(R.id.tv_pkg);
        tvVehicle = findViewById(R.id.tv_vehicle);
        tvStatus = findViewById(R.id.tv_status);
        pb = findViewById(R.id.pb_ota);
        tvProgress = findViewById(R.id.tv_progress);
        btnPick = findViewById(R.id.btn_socket_pick);
        btnProbe = findViewById(R.id.btn_socket_probe);
        btnPush = findViewById(R.id.btn_socket_push);
        btnVehicle = findViewById(R.id.btn_vehicle_probe);
        btnConnect = findViewById(R.id.btn_connect_car);
        btnOneStop = findViewById(R.id.btn_one_stop);
        btnSaveWifi = findViewById(R.id.btn_save_wifi);
        btnGrant = findViewById(R.id.btn_grant_perms);
        etSsid = findViewById(R.id.et_car_ssid);
        etPw = findViewById(R.id.et_car_pw);
        tvScan = findViewById(R.id.tv_wifi_scan);

        prefs = getSharedPreferences("ota_car_wifi", MODE_PRIVATE);
        etSsid.setText(prefs.getString("car_ssid", CarWifi.DEFAULT_SSID));
        etPw.setText(prefs.getString("car_pw", ""));

        // ★ 固定高度的日志区要能自己滚（同 TuneActivity 的套路）：
        //   ① ScrollingMovementMethod 提供滚动行为；② 按下时让父级 NestedScrollView
        //   别拦截事件，手指划的是日志区而不是整页。
        setupLogScrolling(tvLog);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());

        btnPick.setOnClickListener(v -> pickFile.launch("*/*"));
        btnProbe.setOnClickListener(v -> {
            tvStatus.setText("探测中…");
            append("=== 探测 ===");
            // 探测只发版本协商，走 OtaSocketPusher.Callback（本类实现）
            OtaSocketPusher.probe(this);
        });
        btnPush.setOnClickListener(v -> startPush());
        btnVehicle.setOnClickListener(v -> probeVehicle());
        btnConnect.setOnClickListener(v -> connectVehicle());
        btnSaveWifi.setOnClickListener(v -> saveWifi());
        btnGrant.setOnClickListener(v -> openGrantSettings());
        btnOneStop.setOnClickListener(v -> runOneStop());

        // adb 直接跑：am start ... --ez vehicle_probe true
        if (getIntent().getBooleanExtra("vehicle_probe", false)) {
            probeVehicle();
        }

        // 预填 Download / 应用私有目录里已知的包，省得每次都手选
        File f = OtaSocketPusher.locatePackage(this);
        if (f != null) {
            picked = f;
            showPkg(f);
        }

        refreshGrantButton();
    }

    // ==================== 车机 Wi-Fi 设置 / 连接 ====================

    private void saveWifi() {
        String ssid = etSsid.getText().toString().trim();
        String pw = etPw.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            append("车机 Wi-Fi 设置：SSID 不能为空");
            return;
        }
        prefs.edit().putString("car_ssid", ssid).putString("car_pw", pw).apply();
        append("车机 Wi-Fi 设置已保存：SSID=" + ssid
                + (TextUtils.isEmpty(pw) ? "（开放网络）" : "（WPA2 密码已存）"));
    }

    /**
     * 主动去系统设置页授予两个 <b>special</b> 权限（普通弹窗申请不到，只能去设置页开）：
     * <ol>
     *   <li>{@code WRITE_SETTINGS} —— OtaP2p 用 {@code WifiManager.setWifiEnabled()} 开关 Wi-Fi，
     *       API 29+ 必需，否则 {@code SecurityException}；</li>
     *   <li>{@code MANAGE_EXTERNAL_STORAGE} —— 直读 {@code /sdcard/Download/*.zip} 固件包必需
     *       （Android 11+ 非媒体文件直读 {@code EACCES}）。</li>
     * </ol>
     * 逻辑：缺哪个先开哪个；都给了就提示已授权。回程由 {@link #onResume} 续跑 / 刷新按钮文案。
     */
    private void openGrantSettings() {
        if (!Settings.System.canWrite(this)) {
            append("去系统设置页授予「修改系统设置」（写设置）…");
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        if (!Environment.isExternalStorageManager()) {
            append("去系统设置页授予「所有文件访问」…");
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
            return;
        }
        append("✔ 写设置 + 所有文件访问 均已授予");
    }

    /** 根据当前 special 权限状态刷新「去授权」按钮文案，让用户一眼看到还差哪个。 */
    private void refreshGrantButton() {
        if (btnGrant == null) return;
        boolean ws = Settings.System.canWrite(this);
        boolean mes = Environment.isExternalStorageManager();
        if (ws && mes) {
            btnGrant.setEnabled(false);
            btnGrant.setText("✔ 已授权（写设置 / 所有文件）");
        } else {
            btnGrant.setEnabled(true);
            String miss = (!ws && !mes) ? "写设置 + 所有文件访问"
                    : (!ws ? "写设置" : "所有文件访问");
            btnGrant.setText("去授权（还差：" + miss + "）");
        }
    }

    /** 一键「连车机 Wi-Fi」：先确保权限（写设置/存储/附近设备），再扫 AP、自动挑、连上。 */
    private void connectVehicle() {
        ensureAllPermissions(() -> {
            append("=== 连接车辆 Wi-Fi ===");
            setBusy(true);
            tvStatus.setText("连接车辆 Wi-Fi…");
            doConnectVehicle();
        });
    }

    private void doConnectVehicle() {
        List<String> aps = CarWifi.scanCarAp(this);
        String ssid = prefs.getString("car_ssid", CarWifi.DEFAULT_SSID);
        if (aps.isEmpty()) {
            tvScan.setText("未扫到 ZEEHO-* AP（确认车机 Wi-Fi 已开、手机热点已关）");
            append("   附近未扫到 ZEEHO-* AP");
        } else {
            tvScan.setText("附近车机 AP：" + TextUtils.join("  ", aps));
            append("   附近车机 AP：" + TextUtils.join(", ", aps));
            if (!aps.contains(ssid)) {
                ssid = aps.get(0);
                append("   用扫到的 " + ssid);
            }
        }
        String pw = prefs.getString("car_pw", "");
        CarWifi.connect(this, ssid, pw, new CarWifi.ConnectResult() {
            @Override
            public void onResult(boolean ok, String msg) {
                append("   " + msg);
                if (ok) {
                    waitForCarNet(new StepCb() {
                        @Override
                        public void next(boolean ok2, String msg2) {
                            main.post(() -> {
                                setBusy(false);
                                if (ok2) {
                                    tvStatus.setText("已连车机 · " + msg2);
                                    append("✔ 连上车机网段 " + msg2);
                                } else {
                                    tvStatus.setText("连上 AP 但还没拿到 IP");
                                    append("⚠ 已连 AP 但本机还没 192.168.0.x，稍等几秒再探测");
                                }
                            });
                        }
                    });
                } else {
                    main.post(() -> {
                        setBusy(false);
                        tvStatus.setText("连接车辆失败");
                    });
                }
            }
        });
    }

    /** 轮询本机是否拿到车机网段地址（最多 ~12s）。 */
    private void waitForCarNet(StepCb cb) {
        final long deadline = System.currentTimeMillis() + 12000;
        new Thread(() -> {
            OtaSocketPusher.NetInfo n;
            while ((n = OtaSocketPusher.localNetOnCarNet()) == null
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            n = OtaSocketPusher.localNetOnCarNet();
            cb.next(n != null, n != null ? (n.iface + " " + n.ip) : "");
        }, "wait-carnet").start();
    }

    /** 步骤回调：一步的结束（ok + 说明）。 */
    private interface StepCb {
        void next(boolean ok, String msg);
    }

    // ==================== 一站式编排 ====================

    /**
     * 一键 OTA：连接车辆 → 选包 → 探测车辆 → 推送并写入。
     * 每一步失败都给出明确诊断，不静默吞掉。
     */
    private void runOneStop() {
        append("==== 一键 OTA ====");
        append("顺序：连车机 Wi-Fi → 定位固件包 → 探测车辆 → 推送并写入");
        setBusy(true);
        tvStatus.setText("① 连接车辆…");
        ensureAllPermissions(() -> {
            setBusy(true); // 权限绕一圈回来后重新置忙
            stepOneEnsureNetwork(new StepCb() {
            @Override
            public void next(boolean ok, String msg) {
                if (!ok) {
                    finishOneStop(ok, msg);
                    return;
                }
                tvStatus.setText("② 定位固件包…");
                stepTwoEnsurePackage(new StepCb() {
                    @Override
                    public void next(boolean ok, String msg) {
                        if (!ok) {
                            finishOneStop(ok, msg);
                            return;
                        }
                        tvStatus.setText("③ 探测车辆…");
                        append("③ 探测车辆信息…");
                        stepThreeProbeVehicle(new StepCb() {
                            @Override
                            public void next(boolean ok, String msg) {
                                if (!ok) {
                                    finishOneStop(ok, msg);
                                    return;
                                }
                                tvStatus.setText("④ 推送并写入…");
                                append("④ 开始推送（含自动 ack）…");
                                startPush(); // 复用现有前台服务推送；结束会触发 onPushDone
                            }
                        });
                    }
                });
            }
        });
    });
    }

    private void finishOneStop(boolean ok, String msg) {
        main.post(() -> {
            setBusy(false);
            if (ok) {
                tvStatus.setText("一站式完成 · 看车机屏幕");
            } else {
                tvStatus.setText("一站式中断");
                append("✖ " + msg);
            }
        });
    }

    private void stepOneEnsureNetwork(StepCb cb) {
        OtaSocketPusher.NetInfo n = OtaSocketPusher.localNetOnCarNet();
        if (n != null) {
            append("① 已在车机网段：" + n.iface + " " + n.ip);
            cb.next(true, n.iface + " " + n.ip);
            return;
        }
        append("① 手机不在车机网段，自动连车机 AP …");
        doConnectVehicleThen(new StepCb() {
            @Override
            public void next(boolean ok, String msg) {
                cb.next(ok, msg);
            }
        });
    }

    /** 与「连接车辆」按钮同逻辑，但结束时用回调而非直接 setBusy。 */
    private void doConnectVehicleThen(StepCb cb) {
        List<String> aps = CarWifi.scanCarAp(this);
        String ssid = prefs.getString("car_ssid", CarWifi.DEFAULT_SSID);
        if (!aps.isEmpty() && !aps.contains(ssid)) {
            ssid = aps.get(0);
        }
        String pw = prefs.getString("car_pw", "");
        CarWifi.connect(this, ssid, pw, new CarWifi.ConnectResult() {
            @Override
            public void onResult(boolean ok, String msg) {
                append("   " + msg);
                if (ok) {
                    waitForCarNet(cb);
                } else {
                    cb.next(false, "连接车机 Wi-Fi 失败：" + msg);
                }
            }
        });
    }

    private void stepTwoEnsurePackage(StepCb cb) {
        File pkg = picked != null ? picked : OtaSocketPusher.locatePackage(this);
        if (pkg != null) {
            picked = pkg;
            showPkg(pkg);
            append("② 固件包：" + pkg.getName() + "（" + pkg.length() + " 字节）");
            cb.next(true, pkg.getName());
            return;
        }
        // 没找到：自动弹出系统选择器，选完再继续
        append("② 没自动找到包，弹出系统选择器（选 OTA_zip_PATCHED_185_v3.zip）…");
        pendingAfterPick = cb;
        pickFile.launch("*/*");
    }

    /** onPicked 完成后若是一站式在等，继续下一步。 */
    private volatile StepCb pendingAfterPick;

    private void stepThreeProbeVehicle(StepCb cb) {
        OtaSocketPusher.probeVehicle(new OtaSocketPusher.VehicleProbeListener() {
            @Override
            public void onStage(String s) {
                append(s);
            }

            @Override
            public void onResult(final OtaSocketPusher.VehicleInfo info) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tvVehicle.setText(info.summary());
                        if (info.ok) {
                            if (info.vehicleType != null) {
                                append("★ 探测到车型 " + info.vehicleType
                                        + "，推送时用它（车型不符仪表不出进度条）");
                            }
                            append("③ 探测成功：" + info.summary().replace("\n", " "));
                            cb.next(true, info.host);
                        } else {
                            append("③ 探测失败：" + info.error);
                            cb.next(false, info.error + "\n" + info.hint);
                        }
                    }
                });
            }
        });
    }

    // ==================== 权限 ====================

    private void ensureWifiPermission(Runnable action) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                afterWifiPermission = action;
                reqNearbyWifi.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES);
                return;
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 29–32 连指定 Wi-Fi 需要精确定位权限
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
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
            append("⚠ 未授予 Wi-Fi 附近设备权限，无法自动连车机 AP；请去系统设置授权，"
                    + "或手动在系统 Wi-Fi 里连 ZEEHO-xxxx");
            tvStatus.setText("缺少 Wi-Fi 权限");
            setBusy(false);
        }
    }

    // ==================== 特殊权限（设置页授权） ====================

    /**
     * 统一权限闸门：把三类「自动连车机 + 读包 + 控制 Wi-Fi」必需、但分属不同申请方式的权限
     * 一次性跑齐，再执行真正动作 {@code action}：
     * <ol>
     *   <li><b>WRITE_SETTINGS</b>（API 23+，special 级）：OtaP2p 用
     *       {@code WifiManager.setWifiEnabled()} 开关 Wi-Fi，API 29+ 必须有它，否则
     *       SecurityException。只能去系统设置页授予（ACTION_MANAGE_WRITE_SETTINGS）。</li>
     *   <li><b>MANAGE_EXTERNAL_STORAGE</b>（API 30+，special 级）：读 {@code /sdcard/Download/}
     *       下的固件包必需（Android 11+ 非媒体文件直读 EACCES）。同样去设置页授予。</li>
     *   <li><b>NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION</b>（dangerous 级）：自动连车机 AP
     *       走的 WifiNetworkSpecifier 需要，普通弹窗申请（见 {@link #ensureWifiPermission}）。</li>
     * </ol>
     * 前两类要离开 App 去设置页，回来后在 {@link #onResume} 里续跑；第三类弹窗后回调续跑。
     */
    private void ensureAllPermissions(Runnable action) {
        // ① WRITE_SETTINGS（写系统设置）—— special，设置页授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.System.canWrite(this)) {
            pendingAfterPerms = action;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                append("⚠ 无法打开系统设置页，请手动授予「修改系统设置」权限");
            }
            append("需要「修改系统设置」权限（WRITE_SETTINGS）才能控制 Wi-Fi 开关；"
                    + "已打开设置页，授予后返回本页");
            tvStatus.setText("等待系统设置授权");
            setBusy(false);
            return;
        }
        // ② MANAGE_EXTERNAL_STORAGE（所有文件访问）—— special，设置页授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            pendingAfterPerms = action;
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                append("⚠ 无法打开系统设置页，请手动授予「所有文件访问」权限");
            }
            append("需要「所有文件访问」权限才能读取 Download/ 下的固件包；"
                    + "已打开设置页，授予后返回本页");
            tvStatus.setText("等待文件访问授权");
            setBusy(false);
            return;
        }
        // ③ Wi-Fi 附近设备 / 定位 —— 危险级，弹窗申请
        ensureWifiPermission(action);
    }

    /** 从设置页授权回来后，续跑被挂起的权限闸门。 */
    @Override
    protected void onResume() {
        super.onResume();
        refreshGrantButton();
        if (pendingAfterPerms != null) {
            Runnable a = pendingAfterPerms;
            pendingAfterPerms = null;
            // 重新走闸门：若还有未授予的会继续弹设置页，全部齐了才执行 a
            ensureAllPermissions(a);
        }
    }

    // ==================== 选包 / 推送 ====================

    private void onPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        // 读取授权长期有效：SAF 给的 URI 权限要显式 takePersistable，跨重建才不掉
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        append("socket_l：已选 " + uri.getLastPathSegment() + "，拷到私有缓存 …");
        final Uri u = uri;
        final StepCb waiting = pendingAfterPick;
        pendingAfterPick = null;
        new Thread(() -> {
            try {
                File f = OtaSocketPusher.copyFromUri(this, u);
                picked = f;
                main.post(() -> showPkg(f));
                if (waiting != null) {
                    main.post(() -> waiting.next(true, f.getName()));
                }
            } catch (Exception e) {
                append("socket_l：拷贝失败 —— " + e);
                if (waiting != null) {
                    main.post(() -> waiting.next(false, "选包拷贝失败：" + e));
                }
            }
        }, "ota-pick").start();
    }

    private void showPkg(File f) {
        tvPkg.setText(String.format("%s  (%d 字节)", f.getName(), f.length()));
    }

    /** 推送：SAF 选中的优先，其次自动定位；都没有就提示去选包。 */
    private void startPush() {
        File pkg = picked != null ? picked : OtaSocketPusher.locatePackage(this);
        if (pkg == null) {
            tvStatus.setText("还没选包");
            append("socket_l：没找到包 —— 点「选包」用系统文件选择器挑 "
                    + "OTA_zip_PATCHED_185_v3.zip 即可");
            return;
        }
        append("=== 推送 " + pkg.getName() + " ===");
        tvStatus.setText("推送中…");
        pb.setProgress(0);
        tvProgress.setText("0%");
        setBusy(true);
        // 推送走前台服务（保活 + 通知），进度回报走 OtaPushService.Progress（本类实现）
        OtaPushService.setSink(this);
        OtaPushService.start(this, pkg.getAbsolutePath());
    }

    private void setBusy(boolean busy) {
        btnPush.setEnabled(!busy);
        btnProbe.setEnabled(!busy);
        btnPick.setEnabled(!busy);
        btnVehicle.setEnabled(!busy);
    }

    // ==================== 探测车辆 ====================

    /**
     * 一次点清「手机在不在车机网段 → 10950 通不通 → 车机到底是谁」。
     *
     * <p>探测到的车型（从车机回包 {@code versionCode} 的尾巴推出来，
     * 如 {@code SW.1.01.15AE5I → AE5I}）会直接用于之后的 {@code otaStart}；
     * 车型不符时车机 {@code checkUpgradeRequirements()} 返回 2，仪表就收不到进度信号。</p>
     */
    private void probeVehicle() {
        tvVehicle.setText("探测中…");
        tvStatus.setText("探测车辆…");
        append("=== 探测车辆 ===");
        OtaSocketPusher.probeVehicle(new OtaSocketPusher.VehicleProbeListener() {
            @Override
            public void onStage(String s) {
                append(s);
            }

            @Override
            public void onResult(final OtaSocketPusher.VehicleInfo info) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tvVehicle.setText(info.summary());
                        if (info.ok) {
                            tvStatus.setText("已连上车机 · " + info.host);
                            if (info.vehicleType != null) {
                                append("★ 探测到车型 " + info.vehicleType
                                        + "，推送时用它（车型不符仪表不出进度条）");
                            }
                        } else {
                            tvStatus.setText("探测失败 · " + info.error);
                        }
                    }
                });
            }
        });
    }

    // ==================== 日志 ====================

    private void clearLog() {
        log.setLength(0);
        tvLog.setText("（日志已清空）");
    }

    /**
     * 让固定高度的日志 TextView 自己能滚。
     *
     * <p><b>为什么必须两步都做</b>：
     * <ol>
     *   <li>{@code scrollbars="vertical"} 只是画出滚动条，<b>不</b>产生滚动行为 ——
     *       没有 {@link android.text.method.ScrollingMovementMethod} 的 TextView
     *       拿到 ACTION_MOVE 什么都不做，日志多出来就直接被裁掉。</li>
     *   <li>光有 MovementMethod 也不够：外层 NestedScrollView 会先判定「纵向拖拽」把事件截走，
     *       手指划的是整页而不是日志区。所以按下时向 parent 链
     *       {@code requestDisallowInterceptTouchEvent(true)}，抬手再放回去。</li>
     * </ol>
     * </p>
     */
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
                // ★ 返回 false：别把事件吃掉，TextView#onTouchEvent 还得靠它驱动滚动
                return false;
            }
        });
    }

    /** 往日志追加一行（写文件 + 主线程刷新 + 贴底）。 */
    private void append(final String s) {
        HookLog.log(HookLog.OTA + " UI " + s);
        main.post(() -> {
            log.append(s).append('\n');
            // 只留尾部：协议回包那种一行几十字节能刷几千行，全留着 TextView 会卡
            if (log.length() > LOG_MAX_LINES * 100) {
                int nl = log.indexOf("\n");
                if (nl >= 0) {
                    log.delete(0, nl + 1);
                }
            }
            tvLog.setText(log.toString());
            // 贴底显示最新一趟。★ 必须 post 到下一帧 + 用 Layout#getHeight 夹住上限：
            //   在这台 ROM 上 setText 后同一帧内 getLineTop(last) 会返回远超内容高度的值，
            //   一 scroll 就把文字整个顶出可视区。
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

    // ==================== OtaSocketPusher.Callback（探测阶段） ====================

    @Override
    public void onLog(String line) {
        append(line);
    }

    @Override
    public void onProgress(int percent, long sent, long total) {
        if (percent >= 0) {
            main.post(() -> {
                pb.setProgress(Math.max(0, Math.min(100, percent)));
                tvProgress.setText(percent + "%");
            });
        }
    }

    @Override
    public void onDone(boolean ok, String summary) {
        append(ok ? "[完成] " + summary : "[失败] " + summary);
    }

    // ==================== OtaPushService.Progress（推送阶段） ====================

    @Override
    public void onPushLog(String line) {
        append(line);
    }

    @Override
    public void onPushProgress(final int percent, final long sent, final long total,
                               final String note) {
        main.post(() -> {
            pb.setProgress(Math.max(0, Math.min(100, percent)));
            tvProgress.setText(percent + "%");
            tvStatus.setText(String.format("推送中… %d%%  %d/%d MB",
                    percent, sent / 1048576L, total / 1048576L));
        });
    }

    @Override
    public void onPushDone(final boolean ok, final String summary) {
        main.post(() -> {
            setBusy(false);
            if (ok) {
                pb.setProgress(100);
                tvStatus.setText("推送完成 · 看车机屏幕：校验 → 升级 → 重启");
                append("[完成] " + summary);
            } else {
                tvStatus.setText("推送失败");
                append("[失败] " + summary);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Service 还在跑就不要抢 sink（推送中切走界面，进度回报会掉，但传输不中断）
        OtaPushService.setSink(null);
    }
}
