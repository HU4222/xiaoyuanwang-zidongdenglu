package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;

/** Compile-time stub. See {@code de.robv.android.xposed.IXposedHookLoadPackage}. */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Class<?> getXposedVersionClass() {
        return null;
    }
}
