package com.github.zeehospeedhunter.ui;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import com.github.zeehospeedhunter.core.HookLog;

/**
 * 模块 App 进程入口。
 *
 * <p>★ 2026-10-04 起<b>不再有日志悬浮窗</b>。原来 {@code LogHud} 只在
 * {@link OtaActivity} 里挂，实测两个问题：悬浮窗会盖在 ZEEHO App 上（而 OTA 这步本来就要
 * 来回切 App 看状态），而且平白多要一个 {@code SYSTEM_ALERT_WINDOW} 授权页。
 * 现在日志直接显示在 OTA 操作台页内（Tab 切「操作日志 / 车机日志」）。</p>
 *
 * <p>本进程仍然写 {@link HookLog}（落到本模块 files/zeeho_hook.log），
 * 完整历史用 logcat tag {@code ZeehoHook} 或 adb 读文件都能拿到。</p>
 */
public final class UiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 模块自己的进程也开日志（写进本模块 files/zeeho_hook.log），persist 出问题能查到
        HookLog.init(getPackageName(), getApplicationInfo().dataDir);
        HookLog.log(HookLog.CFG + " module app process attached");
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
