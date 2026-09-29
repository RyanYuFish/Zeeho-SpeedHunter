package com.github.zeehospeedhunter.core;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 规避 ZEEHO App 在 Android 16 上的启动崩溃。
 *
 * <p>崩溃栈（tombstone）固定为：</p>
 * <pre>
 *   signal 11 (SIGSEGV), fault addr 0x0000200000000401
 *   #06 [anon:dalvik-DEX data] (com.mob.tools.a.c$c.a+74)
 *   #08 [anon:dalvik-DEX data] (com.mob.tools.a.c$a.a+182)
 * </pre>
 *
 * <p>即 MobTech（推送 / ShareSDK）采集设备信息时，用 JNI 反射把一个带指针标记的
 * 地址当真指针解引用 —— native 层 SIGSEGV，Java 层 try/catch 拦不住，进程直接死。
 * 与本项目 hook 无关（实测：禁用模块后同样崩溃）。</p>
 *
 * <p>规避方式：把这两个采集方法整体替换为「什么都不做」，让 Mob 拿不到设备标识。
 * 代价是该 SDK 的推送 / 分享可能不可用，车辆功能不受影响。</p>
 *
 * <p>开关见 {@link MobOptions#GUARD}；App 升级后若 SDK 改名，日志会打 guard skip。</p>
 */
public final class MobGuard {

    private static final String TAG = HookLog.SPEED;

    /** MobTech 设备信息采集内部类（混淆名，随 SDK 版本变化）。 */
    private static final String[] CLASSES = {
            "com.mob.tools.a.c$c",
            "com.mob.tools.a.c$a",
    };

    private MobGuard() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!MobOptions.guard()) {
            HookLog.log(TAG + " mob guard off");
            return;
        }
        if (!Targets.PACKAGE.equals(lpparam.packageName)) return;
        for (final String name : CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClass(name, lpparam.classLoader);
                final XC_MethodReplacement skip = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        HookLog.log(TAG + " mob guard: skip " + name + "#a");
                        return null;
                    }
                };
                int hooked = 0;
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!"a".equals(method.getName())) continue;
                    XposedBridge.hookMethod(method, skip);
                    hooked++;
                }
                HookLog.log(TAG + " mob guard hooked " + name + " (#a x" + hooked + ")");
            } catch (Throwable t) {
                HookLog.log(TAG + " mob guard skip " + name + " (" + t.getClass().getSimpleName() + ")");
            }
        }
    }
}
