package com.camoverlay.hook

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point, listed in META-INF/xposed/java_init.list. The framework creates one instance per
 * process the module is scoped into.
 *
 * Rule for everything in this module: it runs inside someone else's app. Any failure must end in
 * "CamOverlay does nothing", never in a crashed app.
 */
class CamOverlayModule : XposedModule() {

    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded in ${param.processName} (framework $frameworkName $frameworkVersion, api $apiVersion)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName == OWN_PACKAGE) return
        if (!installed.compareAndSet(false, true)) return

        try {
            val state = OverrideState()
            val interceptor = Camera2Interceptor(this, state)
            interceptor.install()
            hookApplicationCreate(param.packageName, state, interceptor)
            log(Log.INFO, TAG, "hooks installed in ${param.packageName}")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install failed in ${param.packageName}; CamOverlay is inactive here", t)
        }
    }

    /** We need an app Context to read camera capabilities and to register the debug receiver. */
    private fun hookApplicationCreate(pkg: String, state: OverrideState, interceptor: Camera2Interceptor) {
        val method = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val app = chain.getArg(0) as Application
                if (state.attachContext(app)) {
                    DebugReceiver.register(app, this, state, interceptor)
                    log(Log.INFO, TAG, "context attached, debug receiver registered in $pkg")
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "context attach failed", t)
            }
            result
        }
    }

    companion object {
        const val TAG = "CamOverlay"
        const val OWN_PACKAGE = "com.camoverlay"
    }
}
