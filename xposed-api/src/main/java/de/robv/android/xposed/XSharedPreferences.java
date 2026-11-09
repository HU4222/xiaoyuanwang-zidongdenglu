package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time stub of XSharedPreferences.
 * <p>
 * 用于跨进程读取本模块自己的配置文件——被 hook 的目标 App 进程没有权限读
 * 我们的 SharedPreferences 目录，框架提供了忽略权限的直读实现。
 */
public class XSharedPreferences implements android.content.SharedPreferences {

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public XSharedPreferences(File prefFile, boolean worldReadable) {
    }

    public void reload() {
    }

    public boolean makeWorldReadable() {
        return true;
    }

    public File getFile() {
        return null;
    }

    @Override
    public Map<String, ?> getAll() {
        return null;
    }

    @Override
    public String getString(String key, String defValue) {
        return defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return false;
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("XSharedPreferences is read-only");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }
}
