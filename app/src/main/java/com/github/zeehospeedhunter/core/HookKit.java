package com.github.zeehospeedhunter.core;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook 安装工具。
 *
 * <p>本模块只使用「加固后依然稳定」的锚点：AndroidManifest 里的组件类名、layout 里的控件
 * 资源名、公开库（okhttp / okdownload）的类名。目标类不存在时安静跳过并记一行日志，
 * <b>绝不让整包 hook 安装失败</b>。</p>
 */
public final class HookKit {

    private static ClassLoader targetClassLoader;

    private HookKit() {
    }

    /** 目标 App 的 ClassLoader —— 加固后的类要靠它才能找到。 */
    public static void setClassLoader(ClassLoader classLoader) {
        targetClassLoader = classLoader;
    }

    public static ClassLoader classLoader() {
        return targetClassLoader;
    }

    /**
     * 按全限定名找类，找不到返回 {@code null}（不抛异常）。
     *
     * <p>用于「类名不确定是否被加固重定位」的场景：例如公开库被 App 改包名前缀
     * （{@code org.eclipse.paho} → {@code com.cfmoto.org.eclipse.paho}），
     * 两种前缀都要试。用 {@link Class#forName} 走目标 ClassLoader，
     * 避免拿到 boot classloader 里的同名系统类。</p>
     */
    public static Class<?> findClassIfExists(String className) {
        try {
            return Class.forName(className, false, targetClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 类/方法存在才挂，不存在就记 {@code skip}。
     *
     * @param tag 日志前缀（用 {@link HookLog} 里的常量）
     */
    public static void hookIfExists(String tag, String className, String methodName,
                                    XC_MethodHook callback, Object... parameterTypes) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = callback;
            XposedHelpers.findAndHookMethod(className, targetClassLoader, methodName, args);
            HookLog.log(tag + " hooked " + className + "#" + methodName);
        } catch (Throwable t) {
            HookLog.log(tag + " skip " + className + "#" + methodName
                    + " (" + t.getClass().getSimpleName() + ")");
        }
    }
}
