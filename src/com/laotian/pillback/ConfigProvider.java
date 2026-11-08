package com.laotian.pillback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 只读配置提供者。SystemUI 进程通过 ContentResolver.call() 取实时配置，
 * 避免依赖 SharedPreferences 文件权限（现代 Android 上 XSharedPreferences 不可靠）。
 */
public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!Cfg.METHOD_GET.equals(method)) {
            return out;
        }
        SharedPreferences sp = getContext().getSharedPreferences(Cfg.SP_NAME, 0);
        out.putBoolean(Cfg.K_ENABLED, sp.getBoolean(Cfg.K_ENABLED, true));
        out.putBoolean(Cfg.K_VIBRATE, sp.getBoolean(Cfg.K_VIBRATE, true));
        out.putInt(Cfg.K_STRENGTH, sp.getInt(Cfg.K_STRENGTH, 2));
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
