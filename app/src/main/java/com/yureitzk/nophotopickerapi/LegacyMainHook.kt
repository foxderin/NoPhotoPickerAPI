package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Legacy entry point (de.robv.android.xposed api:82), declared in assets/xposed_init.
 * Loaded by legacy-only Xposed frameworks; all logic lives in [HookCore].
 */
class LegacyMainHook : IXposedHookLoadPackage {

    private fun XC_LoadPackage.LoadPackageParam.isSystemFramework(): Boolean {
        return packageName == "android" || appInfo == null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.isSystemFramework() -> hookSystemServices(lpparam)
            lpparam.packageName != null -> {
                hookInstrumentation(lpparam)
                hookActivity(lpparam)
                hookActivityResult(lpparam)
            }
        }
    }

    private fun hookSystemServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        for (className in HookCore.SERVICE_CLASSES) {
            val serviceClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (serviceClass != null) {
                XposedBridge.hookAllMethods(
                    serviceClass,
                    "startActivity",
                    interceptor("System:$className")
                )
                Log.d(HookCore.TAG, "Hooked $className")
                return
            }
        }
    }

    private fun interceptor(source: String): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val context = (param.thisObject as? android.content.Context)
                    ?: args.firstOrNull { it is android.content.Context } as? android.content.Context
                @Suppress("UNCHECKED_CAST")
                HookCore.rewritePickerArgs(args as Array<Any?>, source, context)
            }
        }
    }

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(
                android.app.Instrumentation::class.java,
                "execStartActivity",
                interceptor("Instrumentation.execStartActivity")
            )
            Log.d(HookCore.TAG, "Hooked Instrumentation for ${lpparam.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("${HookCore.TAG}: Failed to hook Instrumentation: ${t.message}")
        }
    }

    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityMethods = listOf("startActivity", "startActivityForResult")

            for (methodName in activityMethods) {
                XposedBridge.hookAllMethods(
                    Activity::class.java,
                    methodName,
                    interceptor("App.Activity.$methodName")
                )
            }
            Log.d(HookCore.TAG, "Successfully hooked Activity methods for ${lpparam.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("${HookCore.TAG}: Failed to hook Activity: ${t.message}")
        }
    }

    private fun hookActivityResult(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        HookCore.sanitizeActivityResultArgs(param.args as Array<Any?>)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("${HookCore.TAG}: Failed to hook onActivityResult: ${t.message}")
        }
    }
}
