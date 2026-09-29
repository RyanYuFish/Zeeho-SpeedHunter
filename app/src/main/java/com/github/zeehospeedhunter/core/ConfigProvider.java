package com.github.zeehospeedhunter.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 把模块开关暴露给 hook 进程（com.cfmoto）读。
 *
 * <p>为什么不用共享文件：Android 10+ 之后 {@code MODE_WORLD_READABLE} 已废，
 * SharedPreferences 目录权限（0751）让别的进程根本走不进来。
 * 导出的 ContentProvider 没有这个问题 —— 目标进程直接 query，
 * 不需要文件权限，也不受分区存储影响。</p>
 *
 * <p>只导出 {@code query}，insert/update/delete 一律拒绝：
 * 配置只允许模块自己的 UI 写。</p>
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.github.zeehospeedhunter.settings";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");
    public static final String COLUMN_KEY = "key";
    public static final String COLUMN_VALUE = "value";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        if (getContext() == null) return null;
        Map<String, ?> all = getContext().getSharedPreferences(
                Keys.PREFS, android.content.Context.MODE_PRIVATE).getAll();
        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_KEY, COLUMN_VALUE});
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            cursor.addRow(new Object[]{entry.getKey(), String.valueOf(value)});
        }
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;   // 只读
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;      // 只读
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;      // 只读
    }
}
