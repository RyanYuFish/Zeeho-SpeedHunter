package com.github.zeehospeedhunter.ui;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * edge-to-edge 的统一处理：内容画到状态栏 / 导航栏底下，栏本身透明。
 *
 * <h3>为什么抽出来</h3>
 * <p>原先只有 {@link MainActivity} 调 {@code setupEdgeToEdge()}，另外两页没调，
 * 于是<b>状态栏图标反色不对</b>：主页在浅色底上是深色图标（对），
 * 但 {@link OtaActivity} / {@link TuneActivity} 的浅色背景上仍是白色图标，
 * 白底白字 ⇒ 时间和电量「消失」了。</p>
 *
 * <p>根因是 {@code windowLightStatusBar} 这个 flag <b>是按窗口记的</b>，
 * 主题里 {@code android:statusBarColor=transparent} 只管颜色不管图标明暗，
 * 所以每个 Activity 都要显式设一次。</p>
 *
 * <h3>为什么还要处理 insets</h3>
 * <p>栏透明之后内容会顶到栏底下，标题和 Tab 就被状态栏压住了。
 * {@link #apply} 把系统栏占位以 padding 形式加回容器顶部，
 * 各页面在 {@code setContentView} 之后调一次即可。</p>
 */
public final class EdgeToEdge {

    private EdgeToEdge() {
    }

    /**
     * 开沉浸 + 修状态栏图标明暗。
     *
     * @param view 根内容视图（一般是 {@code setContentView} 传进去的那个）
     */
    public static void setup(android.app.Activity activity, android.view.View view) {
        try {
            android.view.Window window = activity.getWindow();
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(false);
            }
            // 按当前日/夜模式决定图标明暗（M3 DayNight 跟随系统）
            boolean night = (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            androidx.core.view.WindowInsetsControllerCompat c =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            c.setAppearanceLightStatusBars(!night);
            c.setAppearanceLightNavigationBars(!night);
        } catch (Throwable ignored) {
        }
        apply(view);
    }

    /**
     * 把系统栏高度补成根容器的顶部 padding。
     *
     * <p><b>为什么用 padding 而不是 margin</b>：margin 会把背景一起推出去，
     * 栏那块的底色就露出来了；padding 是「内容内缩」，栏底下仍是页面自己的底色。</p>
     */
    public static void apply(android.view.View view) {
        if (view == null) {
            return;
        }
        try {
            final int top = view.getPaddingTop();
            final int bottom = view.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(v.getPaddingLeft(),
                        top + bars.top,
                        v.getPaddingRight(),
                        bottom + bars.bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }
}
