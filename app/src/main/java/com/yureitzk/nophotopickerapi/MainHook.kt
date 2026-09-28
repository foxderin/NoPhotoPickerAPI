package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Modifier

/**
 * Modern entry point (libxposed API 102), declared in META-INF/xposed/java_init.list.
 * Loaded by LSPosed implementations with libxposed support; all logic lives in [HookCore].
 */
class MainHook : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // System server is handled via onSystemServerStarting; avoid double-hooking.
        if (param.packageName == "android") return

        hookInstrumentation(param.packageName)
        hookActivity(param.packageName)
        hookActivityResult(param.packageName)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        hookSystemServices(param.classLoader)
    }

    private fun hookSystemServices(classLoader: ClassLoader) {
        for (className in HookCore.SERVICE_CLASSES) {
            val serviceClass = findClassIfExists(className, classLoader) ?: continue
            val hooked = hookAllMethods(serviceClass, "startActivity", interceptor("System:$className"))
            if (hooked > 0) {
                Log.d(HookCore.TAG, "Hooked $className ($hooked startActivity methods)")
                return
            }
        }
    }

    private fun findClassIfExists(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            null
        }
    }

    /** Hooks every non-abstract overload of [methodName]; returns the number of hooked methods. */
    private fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        hooker: XposedInterface.Hooker
    ): Int {
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name != methodName || Modifier.isAbstract(method.modifiers)) continue
            try {
                hook(method).intercept(hooker)
                hooked++
            } catch (t: Throwable) {
                Log.w(HookCore.TAG, "Failed to hook ${clazz.name}#$methodName: $t")
            }
        }
        return hooked
    }

    private fun interceptor(source: String) = XposedInterface.Hooker { chain ->
        val args = chain.args.toTypedArray()
        HookCore.rewritePickerArgs(args, source, hookContext(chain.thisObject, args))
        chain.proceed(args)
    }

    /** Any Context reachable from the hooked call: `this` (Activity) or first arg (Instrumentation.who). */
    private fun hookContext(thisObject: Any?, args: Array<Any?>): android.content.Context? {
        (thisObject as? android.content.Context)?.let { return it }
        return args.firstOrNull { it is android.content.Context } as? android.content.Context
    }

    private fun hookInstrumentation(packageName: String) {
        try {
            val hooked = hookAllMethods(
                android.app.Instrumentation::class.java,
                "execStartActivity",
                interceptor("Instrumentation.execStartActivity")
            )
            Log.d(HookCore.TAG, "Hooked Instrumentation for $packageName ($hooked methods)")
        } catch (t: Throwable) {
            Log.e(HookCore.TAG, "Failed to hook Instrumentation: ${t.message}")
        }
    }

    private fun hookActivity(packageName: String) {
        try {
            val activityMethods = listOf("startActivity", "startActivityForResult")

            for (methodName in activityMethods) {
                hookAllMethods(
                    Activity::class.java,
                    methodName,
                    interceptor("App.Activity.$methodName")
                )
            }
            Log.d(HookCore.TAG, "Successfully hooked Activity methods for $packageName")
        } catch (t: Throwable) {
            Log.e(HookCore.TAG, "Failed to hook Activity: ${t.message}")
        }
    }

    private fun hookActivityResult(packageName: String) {
        try {
            val onActivityResult = Activity::class.java.declaredMethods.firstOrNull {
                it.name == "onActivityResult" && it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                )
            } ?: throw NoSuchMethodException("Activity.onActivityResult(int, int, Intent)")

            hook(onActivityResult).intercept(
                XposedInterface.Hooker { chain ->
                    val args = chain.args.toTypedArray()
                    HookCore.sanitizeActivityResultArgs(args)
                    chain.proceed(args)
                }
            )
        } catch (t: Throwable) {
            Log.e(HookCore.TAG, "Failed to hook onActivityResult: ${t.message}")
        }
    }
}
