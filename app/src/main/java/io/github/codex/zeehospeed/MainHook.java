package io.github.codex.zeehospeed;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.codex.zeehospeed.core.HookKit;
import io.github.codex.zeehospeed.core.HookLog;
import io.github.codex.zeehospeed.core.Targets;
import io.github.codex.zeehospeed.ota.OtaHooks;
import io.github.codex.zeehospeed.ride.RideHooks;

/**
 * ZEEHO Speed Hunter —— LSPosed 模块入口。
 *
 * <p>作用域只有 {@code com.cfmoto}（ZEEHO App）。目标 App 的业务代码被爱加密加固，
 * 反编译拿不到类名，因此所有 hook 都建立在「加固后依然稳定」的锚点上：
 * Manifest 里的组件类名、layout 里的控件资源名、公开库的类名。
 * 任何一个锚点失效只会让对应功能变成 no-op，不会让模块整体崩溃。</p>
 *
 * <p>主要能力：</p>
 * <ul>
 *   <li>{@link RideHooks} —— 恢复骑行记录里被隐藏的速度与骑行统计字段；</li>
 *   <li>{@link OtaHooks} —— OTA 界面解锁 + 全链路流量记录（只读，不伪造服务端数据）。</li>
 * </ul>
 *
 * <p>日志出口见 {@link HookLog} —— 这套 LSPosed 不把日志写进 logcat，所以要读文件。</p>
 */
public final class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Targets.PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        HookKit.setClassLoader(lpparam.classLoader);
        HookLog.init(lpparam.packageName,
                lpparam.appInfo == null ? null : lpparam.appInfo.dataDir);

        // 首行日志：pid 用来对齐进程，两个路径用来确认日志到底写去哪了
        HookLog.log("========== " + HookLog.MODULE + " attached: pid=" + Process.myPid()
                + " pkg=" + lpparam.packageName
                + " " + HookLog.describeWriters() + " ==========");

        RideHooks.installAll();
        OtaHooks.installAll();
    }
}
