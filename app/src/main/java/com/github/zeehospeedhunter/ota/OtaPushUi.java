package com.github.zeehospeedhunter.ota;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Targets;

import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在官方App 的 OTA 页面上挂一组「第三方推送」按钮。
 *
 * <p>存在的理由：推送必须在<b>手机侧</b>发起（车机在 {@code 192.168.0.1}，
 * 走 Wi-Fi P2P/AP），而到了车边手上只有手机 —— 所以把
 * {@link OtaSocketPusher} 的能力直接做进模块，随手点一下就能测。</p>
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>按钮挂在 App 自己的 Activity 上，只加一个子 View，不改原有布局与监听；</li>
 *   <li>推送按钮点下先弹一次确认（刷写不可回退），确认后才开线程；</li>
 *   <li>已打过标记，不会重复添加。</li>
 * </ul>
 */
public final class OtaPushUi {

    private static final String TAG = HookLog.OTA;
    private static final int TAG_ID = 0x7ee50;

    private OtaPushUi() {
    }

    static void install() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        if (!Targets.isOta(a)) {
                            return;
                        }
                        View root = a.getWindow() == null
                                ? null : a.getWindow().getDecorView();
                        if (root == null) {
                            return;
                        }
                        // 页面内容不一定立刻就绪，轮询几次
                        for (int i = 0; i < 20; i++) {
                            final Activity act = a;
                            final View r = root;
                            r.postDelayed(() -> inject(act, r), i * 300L);
                        }
                    }
                });
    }

    private static void inject(Activity activity, View root) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewById(TAG_ID) != null) {
            return;
        }

        LinearLayout box = new LinearLayout(activity);
        box.setId(TAG_ID);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        box.setBackgroundColor(0xE6101810);

        TextView title = new TextView(activity);
        title.setText("第三方固件推送（socket_l :10950）");
        title.setTextColor(0xFF8FD1FF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(title);

        TextView status = new TextView(activity);
        status.setTag(0x7ee51);
        status.setTextColor(0xFFB9C7D0);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        box.addView(status);

        File pkg = OtaSocketPusher.locatePackage();
        status.setText(pkg == null
                ? "未找到包：把 OTA_zip_PATCHED_185_v2.zip 放到 /sdcard/Download/"
                : "包：" + pkg.getName() + "（" + pkg.length() + " 字节）");

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(row);

        Button probe = new Button(activity);
        probe.setText("探测");
        probe.setOnClickListener(v -> {
            status.setText("探测中…");
            OtaSocketPusher.probe(new LogCb(status));
        });
        row.addView(probe);

        Button push = new Button(activity);
        push.setText("推送固件");
        push.setOnClickListener(v -> {
            final File f = OtaSocketPusher.locatePackage();
            if (f == null) {
                status.setText("没找到包。adb push 到 /sdcard/Download/ 再回来");
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("推送固件到车机？")
                    .setMessage("包：" + f.getName() + "\n大小：" + f.length() + " 字节\n\n"
                            + "车机会校验并刷写，过程中不能断电、不能动车。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("推送", (d, w) -> {
                        status.setText("推送中…");
                        OtaSocketPusher.push(f, false, new LogCb(status));
                    })
                    .show();
        });
        row.addView(push);

        CheckBox withPrepare = new CheckBox(activity);
        withPrepare.setText("含 prepare");
        withPrepare.setTextColor(0xFFB9C7D0);
        withPrepare.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        withPrepare.setOnCheckedChangeListener((b, on) -> {
            push.setTag(Boolean.valueOf(on));
        });
        push.setTag(Boolean.FALSE);
        box.addView(withPrepare);

        content.addView(box, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        HookLog.log(TAG + " ui | 推送面板已注入 " + activity.getClass().getName());
    }

    /** 把回调输出到页面 TextView + 模块日志。 */
    private static final class LogCb implements OtaSocketPusher.Callback {
        private final TextView tv;
        private final StringBuilder sb = new StringBuilder();

        LogCb(TextView tv) {
            this.tv = tv;
        }

        @Override
        public void onLog(String line) {
            HookLog.log(TAG + " push | " + line);
            synchronized (sb) {
                sb.append(line).append('\n');
                // 只保留末尾若干行，避免无限增长
                while (sb.length() > 1600) {
                    int nl = sb.indexOf("\n");
                    if (nl < 0) {
                        sb.setLength(0);
                        break;
                    }
                    sb.delete(0, nl + 1);
                }
                tv.setText(sb.toString());
            }
        }

        @Override
        public void onDone(boolean ok, String summary) {
            onLog(ok ? "== 完成 ==" : "== 失败 ==");
            onLog(summary);
        }
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
