package com.github.zeehospeedhunter.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.ride.RideConfig;

/**
 * 「压弯标定」页 —— 运行时调参入口。
 *
 * <p><b>为什么单独一页而不是塞进首页</b>：这 8 个参数每个都带「调它会发生什么」的说明，
 * 塞首页会把首页撑成一张表；而且改完必须走「回 App 重算」这套动作，
 * 单独一页可以把「怎么用 / 为什么是这组默认值 / 怎么重算」集中讲清楚。</p>
 *
 * <p><b>参数怎么到 hook 进程</b>：{@code ACTION_CONFIG} 广播 → {@code RemoteSettings}
 * 内存即时生效 + 落盘 {@code zeeho_config.json}。滑块<b>不</b>自动广播 ——
 * 拖动时每帧发一次广播太吵，点「保存并立即生效」才发。</p>
 *
 * <p><b>红线</b>：本页只改「模块自己算出来的数」用到的阈值，不碰任何请求/响应字段。
 * 已落盘的汇总是旧参数算的，必须走「清空账本」才会重算（{@link #requestClearStore()}）。</p>
 */
public final class TuneActivity extends AppCompatActivity {

    private SeekBar sbMinKm, sbCumMin, sbVMin, sbSeed, sbVMax, sbCumCap, sbBrakeCalib, sbCoef;
    private TextView tvMinKm, tvCumMin, tvVMin, tvSeed, tvVMax, tvCumCap, tvBrakeCalib, tvCoef;
    private TextView tvState;
    private TextView tvDebugLog;
    private View boxDebugLog;
    private CompoundButton swDebugBend;

    /**
     * 收 hook 进程回传的账本状态。
     *
     * <p>必须动态注册：{@code ACTION_STORE_STATE} 是本模块自己的 action，
     * 走 LSPosed 注入到 com.cfmoto 进程里发，用 manifest 静态注册收不到。</p>
     */
    /**
     * 收 hook 进程回传的两种东西（同一个 receiver，filter 里两个 action）：
     * <ul>
     *   <li>{@link Keys#ACTION_STORE_STATE} —— 账本状态，进页面时问一次；</li>
     *   <li>{@link Keys#ACTION_BEND_LOG} —— 压弯判定过程，开关打开时 / 从 ZEEHO App
     *       翻完轨迹回到本页时各问一次。</li>
     * </ul>
     * <p>合并成一个 receiver 是因为 {@code registerReceiver} 对同一实例是<b>替换</b>语义，
     * 分两个注册同一个 action 会互相把对方顶掉。</p>
     */
    private final BroadcastReceiver storeStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (Keys.ACTION_STORE_STATE.equals(action)) {
                gotState = true;
                int onFile = intent.getIntExtra(Keys.EXTRA_DAYS_ON_FILE, 0);
                int pending = intent.getIntExtra(Keys.EXTRA_DAYS_PENDING, 0);
                String months = intent.getStringExtra(Keys.EXTRA_MONTHS_PENDING);
                renderState(onFile, pending, months);
            } else if (Keys.ACTION_BEND_LOG.equals(action)) {
                String text = intent.getStringExtra(Keys.EXTRA_BEND_LOG);
                // 空 = 还没有产生过，保持「还没有判定日志」的占位文案
                if (text != null && !text.isEmpty()) {
                    renderBendLog(text);
                }
            }
        }
    };

    /** 本次 onResume 周期内是否已收到 hook 的状态回传（用来挡掉迟到的兜底文案）。 */
    private boolean gotState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tune);
        BackGestureCompat.install(this);
        // ★ 沉浸式 + 状态栏图标明暗：不调的话浅色底上是白图标，等于白底白字
        EdgeToEdge.setup(this, findViewById(R.id.root_tune));

        sbMinKm = findViewById(R.id.sb_min_km);
        sbCumMin = findViewById(R.id.sb_cum_min);
        sbVMin = findViewById(R.id.sb_v_min);
        sbSeed = findViewById(R.id.sb_seed);
        sbVMax = findViewById(R.id.sb_v_max);
        sbCumCap = findViewById(R.id.sb_cum_cap);
        sbBrakeCalib = findViewById(R.id.sb_brake_calib);
        sbCoef = findViewById(R.id.sb_coef);

        tvMinKm = findViewById(R.id.tv_min_km);
        tvCumMin = findViewById(R.id.tv_cum_min);
        tvVMin = findViewById(R.id.tv_v_min);
        tvSeed = findViewById(R.id.tv_seed);
        tvVMax = findViewById(R.id.tv_v_max);
        tvCumCap = findViewById(R.id.tv_cum_cap);
        tvBrakeCalib = findViewById(R.id.tv_brake_calib);
        tvCoef = findViewById(R.id.tv_coef);
        tvState = findViewById(R.id.tv_state);

        load();

        bind(sbMinKm, tvMinKm, "%.1f km", 10);      // 0.0 ~ 4.0 km，step 0.1
        bind(sbCumMin, tvCumMin, "%d°", 1);
        bind(sbVMin, tvVMin, "%d km/h", 1);
        bind(sbSeed, tvSeed, "%d°", 1);
        bind(sbVMax, tvVMax, "%d km/h", 1);
        bind(sbCumCap, tvCumCap, "%d°", 1);
        bind(sbBrakeCalib, tvBrakeCalib, "%.2f", 20);   // 1.0~5.0，step 0.05
        bind(sbCoef, tvCoef, "%.2f", 100);         // 0.10~2.00，step 0.01

        findViewById(R.id.btn_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestClearStore();
            }
        });
        // 顶栏左上角圆片：跟 OTA 页一致，就是 finish() 回上一级（不用 supportNavigateUpTo，
        // 本页没有在 manifest 里声明 parentActivityName）
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        // 顶栏右上角小按钮：只把滑块拨回默认值 + 发一次广播，不 finish / 不 recreate
        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDefaults();
            }
        });

        // ★ onCreate 就注册（不是 onResume）：hook 进程可能在我们 attach 前就已经回过一次，
        //   晚注册会漏掉。onResume 里再注册一次是幂等的（同 action 会覆盖成同一个 receiver）。
        registerStoreState();

        // 诊断：开关在最底下，打开才显示日志区。开关单独 sendBroadcast（不跟「保存」走），
        // 免得只想看日志却把八项参数都重发一遍。
        tvDebugLog = findViewById(R.id.tv_debug_log);
        boxDebugLog = findViewById(R.id.box_debug_log);
        setupLogScrolling(tvDebugLog);
        swDebugBend = (CompoundButton) findViewById(R.id.sw_debug_bend);
        try {
            swDebugBend.setChecked(prefs().getBoolean(Keys.KEY_DEBUG_BEND,
                    RideConfig.debugBendDefault()));
        } catch (Throwable ignored) {
        }
        applyDebugVisibility();
        swDebugBend.setOnCheckedChangeListener((b, on) -> {
            applyDebugVisibility();
            if (on) {
                sendDebugSwitch(true);
                toast("已开启 —— 回 ZEEHO App「历史轨迹」翻一次，判定过程就出现在下面");
            } else {
                sendDebugSwitch(false);
            }
        });
    }

    /** 日志区只在开关打开时占地方；打开就顺手问一次要日志。 */
    private void applyDebugVisibility() {
        boxDebugLog.setVisibility(swDebugBend.isChecked() ? View.VISIBLE : View.GONE);
        if (swDebugBend.isChecked()) {
            queryBendLog();
        }
    }

    /** 只发 debug_bend 这一个键（其余参数不动）。 */
    private void sendDebugSwitch(boolean on) {
        try {
            prefs().edit().putBoolean(Keys.KEY_DEBUG_BEND, on).commit();
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(Keys.ACTION_CONFIG);
            intent.putExtra(Keys.KEY_DEBUG_BEND, on);
            intent.setPackage(Keys.TARGET_PKG);
            sendBroadcast(intent);
            HookLog.log(HookLog.RIDE + " debug_bend -> " + on);
        } catch (Throwable t) {
            toast("开关没送出去：" + t);
        }
    }

    /**
     * 问 hook 进程要压弯判定过程（{@code BENDS} 行）。
     *
     * <p><b>为什么不能自己读文件</b>：{@code BENDS} 是 hook 进程（com.cfmoto）打的，
     * 落在它自己的 {@code /sdcard/Android/data/com.cfmoto/files/zeeho_hook.log}；
     * Android 11+ 不许本模块进程读那个目录（已实测 EACCES）。本模块自己那份同名日志
     * 只记本进程的东西、里面没有 {@code BENDS}。⇒ 只能发广播要。</p>
     */
    private void queryBendLog() {
        try {
            Intent q = new Intent(Keys.ACTION_QUERY_BEND_LOG);
            q.setPackage(Keys.TARGET_PKG);
            sendBroadcast(q);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 让固定高度的日志 TextView 自己能滚。
     *
     * <p><b>为什么必须两步都做</b>：
     * <ol>
     *   <li>{@code scrollbars="vertical"} 只是画出滚动条，<b>不</b>产生滚动行为 ——
     *       没有 {@link android.text.method.ScrollingMovementMethod} 的 TextView
     *       拿到 ACTION_MOVE 什么都不做，日志多出来就直接被裁掉。</li>
     *   <li>光有 MovementMethod 也不够：外层 ScrollView 会先判定「纵向拖拽」把事件截走，
     *       手指划的是整页而不是日志区。所以按下时向 parent 链
     *       {@code requestDisallowInterceptTouchEvent(true)}（这个方法在 ViewGroup 里
     *       会一路向上传递，所以只用对直接 parent 调一次），抬手再放回去。</li>
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

    private void renderBendLog(String text) {
        tvDebugLog.setText(text);
        // 贴底显示最新一趟。★ 必须 post 到下一帧 + 用 Layout#getHeight 夹住上限：
        //   在这台 ROM 上 setText 后同一帧内 getLayout().getLineTop(last) 会返回一个
        //   远超内容高度的值，一 scroll 就把文字整个顶出可视区（OtaActivity 里踩过）。
        tvDebugLog.post(new Runnable() {
            @Override
            public void run() {
                android.text.Layout l = tvDebugLog.getLayout();
                if (l == null) {
                    return;
                }
                int viewH = tvDebugLog.getHeight()
                        - tvDebugLog.getPaddingTop() - tvDebugLog.getPaddingBottom();
                int max = l.getHeight() - viewH;
                tvDebugLog.scrollTo(0, Math.max(0, max));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        gotState = false;
        registerStoreState();
        queryStoreState();
        // 判定过程日志开关是常开的：用户去 ZEEHO App 翻完「历史轨迹」再回来时，
        // 这里要能把刚产生的 BENDS 行取回来（否则得手动开关一次才看得到）。
        if (swDebugBend != null && swDebugBend.isChecked()) {
            queryBendLog();
        }
        android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());
        retry.postDelayed(this::queryStoreState, 1500);
        retry.postDelayed(this::queryStoreState, 4000);
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(storeStateReceiver);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    // ==================== 账本状态（跨进程） ====================

    private void registerStoreState() {
        try {
            IntentFilter f = new IntentFilter(Keys.ACTION_STORE_STATE);
            f.addAction(Keys.ACTION_BEND_LOG);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(storeStateReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(storeStateReceiver, f);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 问 hook 进程要账本状态。
     *
     * <p>本 App 读不到 {@code com.cfmoto} 的 external files（Android 11+ 跨 App 限制），
     * 所以只能发广播问 —— 这是 iOS 版 {@code [ClearStore] cleared N keys} 那行日志
     * 的有状态版：iOS 只能打印，界面上看不到，于是用户忘了重灌、看到的全是 0。</p>
     */
    private void queryStoreState() {
        try {
            Intent q = new Intent(Keys.ACTION_QUERY_STORE_STATE);
            q.setPackage(Keys.TARGET_PKG);
            sendBroadcast(q);
            // hook 进程可能没在跑（ZEEHO App 被杀），2 秒后还没回就显示提示。
            // ★ 用 gotState 守卫，避免兜底文案把<b>迟到</b>的回传覆盖掉（onResume 里连问 3 次，
            //   第 1 次的兜底会在第 2 次回传之后才触发，不挡就会闪回"读不到"）。
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (!gotState) {
                    renderState(0, -1, null);
                }
            }, 2000);
        } catch (Throwable ignored) {
        }
    }

    private void renderState() {
        renderState(0, -1, null);
    }

    /**
     * 画账本状态。
     *
     * @param onFile  已记账天数；<b>0 且 pending&lt;0</b> 表示「没收到回传」（hook 进程没跑）
     * @param pending 待重灌天数，<b>-1</b> 表示未知
     */
    private void renderState(int onFile, int pending, String months) {
        StringBuilder sb = new StringBuilder();
        if (pending < 0) {
            sb.append("账本状态：读不到（ZEEHO App 没在运行，hook 进程未起）。\n")
                    .append("先打开一次 ZEEHO App，本页再回来刷新。");
        } else if (pending == 0) {
            sb.append("账本状态：已记 ").append(onFile).append(" 天，无待重灌。")
                    .append("\n改完参数后：点「清空账本」→ 回 ZEEHO App 逐月翻「历史轨迹」重灌。");
        } else {
            sb.append("账本状态：已记 ").append(onFile).append(" 天，")
                    .append("还有 ").append(pending).append(" 天待重灌");
            if (months != null && !months.isEmpty()) {
                sb.append("（缺 ").append(months.replace(',', '、')).append("）");
            }
            sb.append("。\n★ 这些天的压弯/急刹会显示 0 —— 回 ZEEHO App「我的骑行 → 历史轨迹」")
                    .append("逐月翻一遍，每翻完一个月，上面的数字就少一个月。");
        }
        tvState.setText(sb.toString());
    }

    // ==================== 滑块 ====================

    /**
     * 绑定一个滑块。
     *
     * @param scale <b>乘数</b>：{@code progress = 实际值 × scale}。
     *                 用整数 progress + 乘数避开浮点，代价是精度受限
     *                 （例如 scale=100 时最小步进 0.01）。
     *                 ★ 必须与 {@link #value} / {@link #set} 的 scale 语义一致，
     *                 否则显示值和保存值会差一个数量级。
     */
    private void bind(final SeekBar bar, final TextView label, final String fmt, final double scale) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                // ★ scale 是 double，progress / scale 的结果是 Double；
                //   "%d" 吃 Double 会抛 IllegalFormatConversionException（实测点「恢复默认」
                //   或拖动整数滑块必崩）。%d 的格式串走四舍五入的整数，其余走浮点。
                double v = progress / scale;
                label.setText(fmt.contains("%d")
                        ? String.format(Locale.ROOT, fmt, Math.round(v))
                        : String.format(Locale.ROOT, fmt, v));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
    }

    private double value(SeekBar bar, double scale) {
        return bar.getProgress() / scale;
    }

    private void set(SeekBar bar, double v, double scale) {
        bar.setProgress((int) Math.round(v * scale));
    }

    // ==================== 读写 ====================

    private SharedPreferences prefs() {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void load() {
        SharedPreferences p = prefs();
        set(sbMinKm, p.getFloat(Keys.KEY_BEND_MIN_KM, (float) RideConfig.DEF_BEND_MIN_KM), 10);
        set(sbCumMin, p.getFloat(Keys.KEY_BEND_CUM_MIN, (float) RideConfig.DEF_BEND_CUM_MIN), 1);
        set(sbVMin, p.getFloat(Keys.KEY_BEND_V_MIN, (float) RideConfig.DEF_BEND_V_MIN), 1);
        set(sbSeed, p.getFloat(Keys.KEY_BEND_SEED, (float) RideConfig.DEF_BEND_SEED), 1);
        set(sbVMax, p.getFloat(Keys.KEY_BEND_V_MAX, (float) RideConfig.DEF_BEND_V_MAX), 1);
        set(sbCumCap, p.getFloat(Keys.KEY_BEND_CUM_CAP, (float) RideConfig.DEF_BEND_CUM_CAP), 1);
        set(sbBrakeCalib, p.getFloat(Keys.KEY_BRAKE_CALIB, (float) RideConfig.DEF_BRAKE_CALIB), 20);
        set(sbCoef, p.getFloat(Keys.KEY_BEND_COEF, (float) RideConfig.DEF_BEND_COEF), 100);

        // 触发一次 label 刷新（bind 在 load 之后调用，这里手动同步一次文本）
        syncLabels();
    }

    private void syncLabels() {
        tvMinKm.setText(String.format(Locale.ROOT, "%.1f km", value(sbMinKm, 10)));
        tvCumMin.setText(String.format(Locale.ROOT, "%d°", (int) value(sbCumMin, 1)));
        tvVMin.setText(String.format(Locale.ROOT, "%d km/h", (int) value(sbVMin, 1)));
        tvSeed.setText(String.format(Locale.ROOT, "%d°", (int) value(sbSeed, 1)));
        tvVMax.setText(String.format(Locale.ROOT, "%d km/h", (int) value(sbVMax, 1)));
        tvCumCap.setText(String.format(Locale.ROOT, "%d°", (int) value(sbCumCap, 1)));
        tvBrakeCalib.setText(String.format(Locale.ROOT, "%.2f", value(sbBrakeCalib, 20)));
        tvCoef.setText(String.format(Locale.ROOT, "%.2f", value(sbCoef, 100)));
    }

    /**
     * 「恢复默认参数」：把八个滑块直接拨回 {@code RideConfig.DEF_*} 标准值并立即生效。
     *
     * <p><b>不需要重启 ZEEHO App</b>：参数走 {@link Keys#ACTION_CONFIG} 广播，
     * hook 进程 {@code RemoteSettings#liveConfig} 内存即时生效（下一个响应就用新值），
     * 同时落盘 {@code zeeho_config.json} 供冷启动恢复。所以这里调一次 {@link #apply()} 就完事。</p>
     *
     * <p>唯一还需要手工做的是<b>重灌账本</b>：已经落盘的汇总是旧参数算的，
     * 轨迹不常驻、删了只能回 App 逐月翻「历史轨迹」才能重算 —— 这一步没法在模块里代劳，
     * 弹窗里说清楚。</p>
     */
    private void resetDefaults() {
        set(sbMinKm, RideConfig.DEF_BEND_MIN_KM, 10);
        set(sbCumMin, RideConfig.DEF_BEND_CUM_MIN, 1);
        set(sbVMin, RideConfig.DEF_BEND_V_MIN, 1);
        set(sbSeed, RideConfig.DEF_BEND_SEED, 1);
        set(sbVMax, RideConfig.DEF_BEND_V_MAX, 1);
        set(sbCumCap, RideConfig.DEF_BEND_CUM_CAP, 1);
        set(sbBrakeCalib, RideConfig.DEF_BRAKE_CALIB, 20);
        set(sbCoef, RideConfig.DEF_BEND_COEF, 100);
        syncLabels();
        apply();     // 立即广播生效，不用重启 ZEEHO App
        toast("已恢复默认参数并立即生效（无需重启 App）");
    }

    private void apply() {
        float minKm = (float) value(sbMinKm, 10);
        float cumMin = (float) value(sbCumMin, 1);
        float vMin = (float) value(sbVMin, 1);
        float seed = (float) value(sbSeed, 1);
        float vMax = (float) value(sbVMax, 1);
        float cumCap = (float) value(sbCumCap, 1);
        float brakeCalib = (float) value(sbBrakeCalib, 20);
        float coef = (float) value(sbCoef, 100);

        // 本地留一份（冷启动时回填滑块位置；真正生效靠广播）
        try {
            prefs().edit()
                    .putFloat(Keys.KEY_BEND_MIN_KM, minKm)
                    .putFloat(Keys.KEY_BEND_CUM_MIN, cumMin)
                    .putFloat(Keys.KEY_BEND_V_MIN, vMin)
                    .putFloat(Keys.KEY_BEND_SEED, seed)
                    .putFloat(Keys.KEY_BEND_V_MAX, vMax)
                    .putFloat(Keys.KEY_BEND_CUM_CAP, cumCap)
                    .putFloat(Keys.KEY_BRAKE_CALIB, brakeCalib)
                    .putFloat(Keys.KEY_BEND_COEF, coef)
                    .commit();
        } catch (Throwable ignored) {
        }

        // 合并成一次广播：把首页那几个开关也一起带上，免得覆盖掉它们的值
        SharedPreferences main = prefs();
        Intent intent = new Intent(Keys.ACTION_CONFIG);
        intent.putExtra(Keys.KEY_WEB_LAYER, main.getBoolean(Keys.KEY_WEB_LAYER, true));
        intent.putExtra(Keys.KEY_GATE_ANALYSE, main.getBoolean(Keys.KEY_GATE_ANALYSE, true));
        intent.putExtra(Keys.KEY_GATE_EVENT, main.getBoolean(Keys.KEY_GATE_EVENT, true));
        intent.putExtra(Keys.KEY_VIEW_LAYER, main.getBoolean(Keys.KEY_VIEW_LAYER, false));
        intent.putExtra(Keys.KEY_RIDE_FILL, main.getBoolean(Keys.KEY_RIDE_FILL, true));
        intent.putExtra(Keys.KEY_RIDE_FULLSCAN, true);
        intent.putExtra(Keys.KEY_TUNE_EXPORT, main.getBoolean(Keys.KEY_TUNE_EXPORT, false));
        intent.putExtra(Keys.KEY_BEND_MIN_KM, minKm);
        intent.putExtra(Keys.KEY_BEND_CUM_MIN, cumMin);
        intent.putExtra(Keys.KEY_BEND_V_MIN, vMin);
        intent.putExtra(Keys.KEY_BEND_SEED, seed);
        intent.putExtra(Keys.KEY_BEND_V_MAX, vMax);
        intent.putExtra(Keys.KEY_BEND_CUM_CAP, cumCap);
        intent.putExtra(Keys.KEY_BRAKE_CALIB, brakeCalib);
        intent.putExtra(Keys.KEY_BEND_COEF, coef);
        android.widget.CompoundButton swDebug =
                (android.widget.CompoundButton) findViewById(R.id.sw_debug_bend);
        boolean debug = swDebug != null && swDebug.isChecked();
        intent.putExtra(Keys.KEY_DEBUG_BEND, debug);
        try {
            prefs().edit().putBoolean(Keys.KEY_DEBUG_BEND, debug).commit();
        } catch (Throwable ignored) {
        }
        intent.setPackage(Keys.TARGET_PKG);   // ★ 见 Keys#TARGET_PKG：不加这行 Android 8+ 会拦掉
        sendBroadcast(intent);

        HookLog.log(HookLog.RIDE + " tune applied: minKm=" + minKm + " cum=" + cumMin
                + " vMin=" + vMin + " seed=" + seed + " vMax=" + vMax
                + " cap=" + cumCap + " coef=" + coef + " brakeCalib=" + brakeCalib);
        toast("已保存并生效 —— 回 ZEEHO App 翻「历史轨迹」即可看到新数字");
    }

    /**
     * 请求 hook 进程清空按天账本。
     *
     * <p>必须走广播：账本在 {@code com.cfmoto} 的 external files 下，Android 11+
     * 不许别的 App 读那个目录（已实测 EACCES），只有 hook 进程自己删得了。</p>
     */
    private void requestClearStore() {
        Intent clear = new Intent(Keys.ACTION_CLEAR_DAY_STORE);
        clear.setPackage(Keys.TARGET_PKG);     // ★ 同上
        sendBroadcast(clear);
        toast("已清空账本 —— 回 ZEEHO App 逐月翻「历史轨迹」重新灌一遍");
        queryStoreState();
    }

    private void toast(String text) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
