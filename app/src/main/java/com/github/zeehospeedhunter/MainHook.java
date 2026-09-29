package com.github.zeehospeedhunter;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.MobGuard;
import com.github.zeehospeedhunter.core.Targets;
import com.github.zeehospeedhunter.ble.BleHooks;
import com.github.zeehospeedhunter.net.NetPatch;
import com.github.zeehospeedhunter.ota.OtaHooks;
import com.github.zeehospeedhunter.ride.RideHooks;

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
 *   <li>{@code net.NetPatch} —— <b>主机制</b>：改网络层的车型能力位
 *       （vehicleKinds / cyclingEventStatisticFlag），让 App 自己显示骑行分析入口与
 *       极速/急加速/急刹/压弯；这条线来自 iOS 端 8 轮消融的结论，与 Shadowrocket
 *       脚本 {@code ZeehoVehicleType.min.js} 处理的是同一组字段。</li>
 *   <li>{@link RideHooks} —— 视图层兜底（实时开关，默认关，见 {@code ride.RideOptions}）；</li>
 *   <li>{@link OtaHooks} —— OTA 界面解锁 + 全链路流量记录（只读，不伪造服务端数据）；</li>
 *   <li>{@link BleHooks} —— BLE 车控帧双向捕获。</li>
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

        MobGuard.install(lpparam);   // 先保命：规避 MobTech SDK 的启动崩溃
        NetPatch.installAll();       // 主机制：网络层能力位改写
        RideHooks.installAll();      // 视图层兜底（默认关）
        OtaHooks.installAll();
        BleHooks.installAll();
    }
}
