package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/** Compile-time stub. See {@code de.robv.android.xposed.IXposedHookLoadPackage}. */
public abstract class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;

        public LoadPackageParam() {
        }
    }
}
