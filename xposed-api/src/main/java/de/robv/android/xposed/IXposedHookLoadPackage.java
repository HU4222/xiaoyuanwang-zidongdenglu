package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Compile-time stub of the Xposed / LSPosed API (version 93 surface).
 * <p>
 * This class is intentionally NOT packaged into the APK (declared as
 * compileOnly). At runtime the real implementation is injected by the
 * framework into the zygote process. Shipping our own copy would shadow the
 * framework class and break every hook, so never add this module as
 * an "implementation" dependency.
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
