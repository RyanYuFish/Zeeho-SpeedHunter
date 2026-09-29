package com.github.zeehospeedhunter.core;

/**
 * {@link MobGuard} 的开关。
 *
 * <p>默认开启：不开的话 ZEEHO App 在 Android 16 上启动即 native 崩溃（MobTech SDK），
 * 任何 hook 都没机会生效。关掉它就回到 App 原生行为。</p>
 */
public final class MobOptions {

    /** 规避 MobTech SDK 的启动崩溃。关掉 = 不干预 App 原生行为。 */
    private static final boolean GUARD = true;

    private MobOptions() {
    }

    public static boolean guard() {
        return RemoteSettings.getBool(Keys.KEY_MOB_GUARD, GUARD);
    }
}
