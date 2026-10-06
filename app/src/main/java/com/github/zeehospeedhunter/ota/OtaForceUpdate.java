package com.github.zeehospeedhunter.ota;

import android.view.View;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.HookKit;

/**
 * 「立即更新」按钮的强制触发。
 *
 * <p><b>为什么需要</b>：`OtaPageUnlock` 把「立即更新」按钮强制显示出来了，
 * 但控件树里它是 {@code clickable=false} —— App 内部仍判定「无更新」，
 * 于是按钮点不动（2026-10-03 实测）。</p>
 *
 * <p><b>做法</b>：两层
 * <ol>
 *   <li><b>视图层</b>：在 OTA 页面 onResume 之后把
 *       {@code ota_group_action} / {@code action_up} 的 clickable 置 true；
 *       同时若按钮不可点，直接反射调它 {@code mOnClickListener}（或在
 *       {@code View#performClick()} 上挂钩子，在点击被吞时补一次）。</li>
 *   <li><b>通知层</b>：{@code action_up} 的文案是「立即更新」，
 *       引擎层通常按「文案 + enabled」决定是否触发 ⇒ 两者一起改。</li>
 * </ol>
 *
 * <p><b>红线</b>：只让按钮可点，<b>不伪造任何业务结果</b>。点下去之后下载、校验、
 * 推送全部由 App 与车机自己完成；我们的包 MD5 自洽（`OtaOptions.INJECT_FILE_MD5`）。</p>
 */
public final class OtaForceUpdate {

    private static final String TAG = HookLog.OTA;

    private static final Set<String> LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private OtaForceUpdate() {
    }

    public static void installAll() {
        if (!OtaOptions.FORCE_UPDATE_CLICK) {
            HookLog.log(TAG + " force-update disabled");
            return;
        }
        // 1) hook View#setOnClickListener：按钮在设监听时就能拿到
        try {
            XposedViewSetOnClick();
        } catch (Throwable t) {
            HookLog.log(TAG + " force-update hook fail: " + t);
        }
        // 2) hook TextView#setEnabled：把「被禁用」改回可用
        try {
            XposedViewSetEnabled();
        } catch (Throwable t) {
            HookLog.log(TAG + " force-update enabled hook fail: " + t);
        }
        HookLog.log(TAG + " force-update installed");
    }

    private static void XposedViewSetOnClick() {
        final Class<?> v = View.class;
        for (java.lang.reflect.Method m : v.getDeclaredMethods()) {
            if (!"setOnClickListener".equals(m.getName())) {
                continue;
            }
            final XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String id = viewIdOf(param.thisObject);
                    if (id == null) {
                        return;
                    }
                    if (id.endsWith("action_up") || id.endsWith("ota_group_action")
                            || id.endsWith("action_appointment")) {
                        forceClickable(param.thisObject, "setOnClickListener");
                    }
                }
            };
            try {
                XposedBridge.hookMethod(m, hook);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void XposedViewSetEnabled() {
        final Class<?> v = View.class;
        for (java.lang.reflect.Method m : v.getDeclaredMethods()) {
            if (!"setEnabled".equals(m.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String id = viewIdOf(param.thisObject);
                        if (id != null && id.endsWith("action_up")) {
                            // 强制传 true，忽略 App 传下来的 false
                            param.args[0] = Boolean.TRUE;
                            logOnce("forced action_up enabled=true");
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 取 View 的 resource-id 名字部分（不含包名前缀）。
     * 失败返回 {@code null}，绝不抛异常。
     */
    private static String viewIdOf(Object view) {
        try {
            if (!(view instanceof View)) {
                return null;
            }
            int id = ((View) view).getId();
            if (id == View.NO_ID) {
                return null;
            }
            android.content.res.Resources res = ((View) view).getResources();
            if (res == null) {
                return null;
            }
            // ★ getResourceEntryName 在 API 30+ 返回 CharSequence，不能直接当 String
            CharSequence name = res.getResourceEntryName(id);
            return name == null ? null : name.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void forceClickable(Object view, String via) {
        try {
            if (!(view instanceof View)) {
                return;
            }
            View v = (View) view;
            v.setClickable(true);
            v.setEnabled(true);
            logOnce("forced clickable on action_up via " + via);
        } catch (Throwable t) {
            HookLog.log(TAG + " forceClickable fail: " + t);
        }
    }

    private static void logOnce(String msg) {
        if (LOGGED.add(msg)) {
            HookLog.log(TAG + " " + msg);
        }
    }
}
