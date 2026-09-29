package com.github.zeehospeedhunter.ui;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import com.github.zeehospeedhunter.core.HookLog;

/** 模块 App 进程入口：启用 Material You 动态取色 + 初始化自诊断日志。 */
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

