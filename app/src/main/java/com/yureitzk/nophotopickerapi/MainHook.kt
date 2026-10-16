package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Modifier

class MainHook : XposedModule() {

    companion object {
        private const val TAG = "NoPhotoPicker"
        private const val FLAG = "x_handled_by_nophoto"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // Framework classes need one hook per process; system_server has its own callback.
        if (!param.isFirstPackage || param.packageName == "android") return
        hookInstrumentation(param.packageName)
        hookActivity(param.packageName)
        hookActivityResult(param.packageName)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        hookSystemServices(param.classLoader)
    }

    private fun hookSystemServices(classLoader: ClassLoader) {
        val serviceClasses = listOf(
            "com.android.server.wm.ActivityTaskManagerService",
            "com.android.server.am.ActivityManagerService",
            "com.android.server.wm.ActivityStarter"
        )

        for (className in serviceClasses) {
            val serviceClass = try {
                Class.forName(className, false, classLoader)
            } catch (t: Throwable) {
                continue
            }
            val hooked = hookAllMethods(serviceClass, "startActivity", createIntentInterceptor("System:$className"))
            if (hooked > 0) {
                Log.d(TAG, "Hooked $className ($hooked startActivity methods)")
                return
            }
        }
    }

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
                Log.w(TAG, "Failed to hook ${clazz.name}#$methodName: $t")
            }
        }
        return hooked
    }

    private fun createIntentInterceptor(source: String) = XposedInterface.Hooker { chain ->
        for (i in chain.args.indices) {
            val intent = chain.args[i] as? Intent ?: continue
            if (!isPhotoPickerIntent(intent)) continue
            logIntentDetails(intent, source)
            val newIntent = buildDocumentPickerIntent(intent)
            val args = chain.args.toTypedArray()
            args[i] = newIntent

            // A null Bundle is not a resolvedType; use the hooked signature.
            if (chain.executable.parameterTypes.getOrNull(i + 1) == String::class.java) {
                args[i + 1] = newIntent.type
            }
            return@Hooker chain.proceed(args)
        }
        chain.proceed()
    }

    private fun hookInstrumentation(packageName: String) {
        try {
            hookAllMethods(
                android.app.Instrumentation::class.java,
                "execStartActivity",
                createIntentInterceptor("Instrumentation.execStartActivity")
            )
            Log.d(TAG, "Hooked Instrumentation for $packageName")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook Instrumentation: ${t.message}")
        }
    }

    private fun hookActivity(packageName: String) {
        try {
            val activityMethods = listOf("startActivity", "startActivityForResult")

            for (methodName in activityMethods) {
                hookAllMethods(
                    Activity::class.java,
                    methodName,
                    createIntentInterceptor("App.Activity.$methodName")
                )
            }
            Log.d(TAG, "Successfully hooked Activity methods for $packageName")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook Activity: ${t.message}")
        }
    }

    private fun hookActivityResult(packageName: String) {
        try {
            val onActivityResult = Activity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java
            )
            hook(onActivityResult).intercept(XposedInterface.Hooker { chain ->
                val resultCode = chain.getArg(1) as Int
                val data = chain.getArg(2) as? Intent
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val hasContent = when {
                        data.data != null -> true
                        data.clipData?.let { clipData ->
                            (0 until clipData.itemCount).any { clipData.getItemAt(it).uri != null }
                        } ?: false -> true
                        data.hasExtra(Intent.EXTRA_STREAM) -> true
                        data.hasExtra(Intent.EXTRA_CONTENT_ANNOTATIONS) -> true
                        else -> false
                    }
                    if (!hasContent) {
                        Log.d(TAG, "Empty result detected for request ${chain.getArg(0)}")
                        val args = chain.args.toTypedArray()
                        args[1] = Activity.RESULT_CANCELED
                        args[2] = null
                        return@Hooker chain.proceed(args)
                    }
                }
                chain.proceed()
            })
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook onActivityResult for $packageName: ${t.message}")
        }
    }

    private fun getMaxItems(intent: Intent): Int {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
        ) {
            intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)
        } else {
            -1
        }
    }

    private fun isPhotoPickerIntent(intent: Intent): Boolean {
        if (intent.hasExtra(FLAG)) return false
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                intent.action == MediaStore.ACTION_PICK_IMAGES
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2 ->
                intent.action == MediaStore.ACTION_PICK_IMAGES ||
                        intent.action == "androidx.activity.result.contract.action.PickVisualMedia"

            else -> false
        }
    }

    private fun logIntentDetails(intent: Intent, source: String) {
        Log.d(TAG, "[$source] Photo picker detected")
        Log.d(TAG, "  Action: ${intent.action}")
    }

    private fun buildDocumentPickerIntent(original: Intent): Intent {
        // Android 16 may route GET_CONTENT back to the Photo Picker.
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Intent.ACTION_OPEN_DOCUMENT
        } else {
            Intent.ACTION_GET_CONTENT
        }
        return Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)

            val mimeTypes = (original.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                ?: original.getStringArrayExtra("android.provider.extra.MIME_TYPES")
                ?: original.getStringArrayExtra("androidx.activity.result.contract.extra.PickVisualMedia.MimeType"))
                ?.takeIf { it.isNotEmpty() }
                ?: original.type?.let { arrayOf(it) }
                ?: arrayOf("image/*", "video/*")

            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1 || mimeTypes[0] != type) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }

            val allowMultiple = original.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            val maxItems = getMaxItems(original)

            val shouldAllowMultiple = when {
                allowMultiple -> true
                maxItems > 1 -> true
                else -> false
            }

            if (shouldAllowMultiple) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                Log.d(TAG, "Multi-select enabled")
            }

            putExtra(FLAG, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.d(TAG, "Created document picker intent")
        }
    }
}
