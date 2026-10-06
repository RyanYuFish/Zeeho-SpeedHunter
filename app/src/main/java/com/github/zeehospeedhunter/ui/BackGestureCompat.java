package com.github.zeehospeedhunter.ui;

import android.app.Activity;
import android.os.Build;

import com.github.zeehospeedhunter.core.HookLog;

/**
 * Android 13+ 预测性返回手势兼容层。
 *
 * <p><b>★ 本类唯一要做的事是「什么都别注册」。</b>别看它现在只有一行日志就以为可以删 ——
 * 保留它是为了让「为什么这里不能加 {@code OnBackPressedCallback}」这条结论有个落脚点。</p>
 *
 * <h3>踩过的坑（2026-10-06，Nothing A024 / Android 16 / targetSdk 34）</h3>
 * <p>只要 App 注册了<b>会消费返回事件</b>的回调 —— 安卓 X {@code OnBackPressedCallback}，
 * 或 {@code OnBackInvokedDispatcher} 上 {@code PRIORITY_DEFAULT}（0）的
 * {@code OnBackInvokedCallback} —— 框架就把这次手势判给 App：</p>
 * <pre>
 *   CoreBackPreview: startBackNavigation ... mIsAnimationCallback=true
 *   ShellBackPreview: BackNavigationInfo{mType=TYPE_CALLBACK, mAnimationCallback=true}
 * </pre>
 * <p>含义是「App 自己按进度画动画」，于是系统的「返回主屏幕 / 跨 activity」预览<b>不再播放</b>。
 * 而 {@code OnBackPressedCallback} 若只实现了 {@code handleOnBackPressed()}（本类上一版就是这么写的），
 * 没有 {@code handleOnBackStarted/Progressed}，滑动全程画面纹丝不动、松手才瞬间关掉 ——
 * 表现就是「预测性返回手势没反应 / 触发不了」。</p>
 *
 * <p>修复：不注册消费型回调，让框架走默认返回行为（finish + 系统动画）。
 * 清单里的 {@code android:enableOnBackInvokedCallback="true"} 负责打开系统动画；
 * 没有消费型回调时，安卓 X 的 {@code OnBackPressedDispatcher} 不会往平台挂任何东西，
 * 系统动画照常播放。实测修复后：</p>
 * <pre>
 *   CoreBackPreview: onTransactionReady, opening: [Task home], closing: [Task 本模块]
 *   ShellBackPreview: Back animation transition ... FLAG_BACK_GESTURE_ANIMATED
 * </pre>
 * <p>（滑动中途截图能看到 App 窗口缩小、四周露出壁纸 —— 就是「返回主屏幕」预览。）</p>
 *
 * <h3>另外两条实测结论</h3>
 * <ul>
 *   <li>Android 16 的 {@code PRIORITY_SYSTEM_NAVIGATION_OBSERVER}（= -1）对普通 App <b>不可用</b>：
 *       {@code registerOnBackInvokedCallback(-1, cb)} 直接抛
 *       {@code IllegalArgumentException: Application registered OnBackInvokedCallback
 *       cannot have negative priority}。想要「只观察、不消费」在本机走不通，
 *       所以这里干脆什么都不注册。</li>
 *   <li>没有任何回调时返回依然正常：{@code onBackNavigationDone backType=1, triggerBack=true}，
 *       activity 正常 finish（API &lt; 33 走 {@code onBackPressed()} 默认实现，同样 finish）。</li>
 * </ul>
 *
 * <p><b>将来若确实要拦截返回</b>（例如某页有未保存内容要弹确认），必须实现
 * {@code handleOnBackStarted/Progressed/Cancelled} 自己画动画，并接受系统动画被接管这件事。</p>
 */
public final class BackGestureCompat {

    private BackGestureCompat() {
    }

    /**
     * @param activity 页面；这里只记一行日志，<b>刻意不注册任何回调</b>（原因见类注释）。
     */
    public static void install(final Activity activity) {
        HookLog.log("ZeehoUI back | no consuming callback (system animation kept) api="
                + Build.VERSION.SDK_INT + " activity=" + activity.getClass().getSimpleName());
    }
}
