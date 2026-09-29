package com.github.zeehospeedhunter.core;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

/** View 通用工具：资源名、递归恢复可见、宿主 Activity 查找。 */
public final class ViewKit {

    private ViewKit() {
    }

    /** 取控件的资源名（= layout 里的 {@code android:id}），取不到返回 {@code null}。 */
    public static String resourceName(View view) {
        if (view == null) return null;
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 恢复可见 —— 连所有不可见的祖先一起恢复。
     * App 隐藏某个字段时常常是藏整个容器，只改自己没用。
     */
    public static void reveal(View view) {
        if (view == null) return;
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
        View parent = parentOf(view);
        while (parent != null && parent.getVisibility() != View.VISIBLE) {
            parent.setVisibility(View.VISIBLE);
            parent = parentOf(parent);
        }
    }

    private static View parentOf(View view) {
        return view.getParent() instanceof View ? (View) view.getParent() : null;
    }

    /** 从 View 的 Context 往上找宿主 Activity（可能被 ContextWrapper 层层包装）。 */
    public static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
