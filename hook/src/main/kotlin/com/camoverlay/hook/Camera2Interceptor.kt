package com.camoverlay.hook

import android.hardware.camera2.CaptureRequest
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hooks android.hardware.camera2.impl.CameraDeviceImpl.submitCaptureRequest, the private method
 * every Camera2 submission (capture, captureBurst, setRepeatingRequest, setRepeatingBurst, and
 * therefore CameraX and WebRTC's Camera2Capturer) funnels into.
 *
 * AOSP signature (may differ per Android version, so we match by name and inspect arguments):
 *   int submitCaptureRequest(List<CaptureRequest> requestList, CaptureCallback callback,
 *                            Executor executor, boolean repeating)
 */
class Camera2Interceptor(
    private val xposed: XposedInterface,
    private val state: OverrideState,
) {
    private val rewriter = RequestRewriter()
    private val logBudget = AtomicInteger(LOG_FIRST_N)

    fun install() {
        val impl = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl")

        val submits = impl.declaredMethods.filter { it.name == "submitCaptureRequest" }
        if (submits.isEmpty()) {
            log(Log.ERROR, "submitCaptureRequest not found (renamed, or hidden-API restrictions apply to the module); nothing hooked")
            return
        }
        for (m in submits) {
            log(Log.INFO, "hooking $m")
            xposed.hook(m).intercept { chain -> onSubmit(chain) }
        }

        impl.declaredMethods.filter { it.name == "close" && it.parameterCount == 0 }.forEach { m ->
            xposed.hook(m).intercept { chain ->
                state.onDeviceClosed(chain.thisObject)
                chain.proceed()
            }
        }

        if (!rewriter.available) {
            log(Log.WARN, "request rewriting unavailable (${rewriter.unavailableReason}); observe-only mode")
        }
    }

    private fun onSubmit(chain: XposedInterface.Chain): Any? {
        val newArgs: Array<Any?>? = try {
            rewriteArgs(chain)
        } catch (t: Throwable) {
            state.disable()
            xposed.log(Log.ERROR, TAG, "rewrite failed; CamOverlay disabled for this process", t)
            null
        }
        // The original call always goes ahead, with or without our changes.
        return if (newArgs == null) chain.proceed() else chain.proceed(newArgs)
    }

    /** @return replacement arguments, or null to pass the call through untouched. */
    private fun rewriteArgs(chain: XposedInterface.Chain): Array<Any?>? {
        val source = chain.args
        val args = arrayOfNulls<Any>(source.size)
        for (i in source.indices) args[i] = source[i]
        val listIndex = args.indexOfFirst { it is List<*> }
        if (listIndex < 0) return null
        val requests = (args[listIndex] as List<*>).filterIsInstance<CaptureRequest>()
        if (requests.isEmpty()) return null
        val repeating = args.firstOrNull { it is Boolean } as? Boolean ?: false

        if (repeating) {
            // Keep a copy: args is modified below, and restore must replay the app's own requests.
            state.lastRepeating = RepeatingSubmission(chain.thisObject, chain.executable as Method, args.copyOf())
        }

        val overrides = state.snapshot()
        if (logBudget.getAndDecrement() > 0) {
            log(Log.INFO, "submit repeating=$repeating n=${requests.size} ${describe(requests[0])} overrides=$overrides")
        }
        if (overrides.isEmpty() || !rewriter.available) return null

        val cap = state.capabilityFor(chain.thisObject)
        if (cap == null) {
            log(Log.WARN, "no capabilities for this camera yet; passing through")
            return null
        }

        val rewritten = requests.map { rewriter.rewrite(it, overrides, cap) }
        if (rewritten.indices.all { rewritten[it] === requests[it] }) return null
        args[listIndex] = rewritten
        return args
    }

    /**
     * Makes the current override map take effect now, by replaying the app's last repeating
     * request through the hook chain. With an empty map this is the restore path: the app's own
     * request goes back to the camera unmodified.
     */
    fun resubmitRepeating(): Boolean {
        val last = state.lastRepeating ?: return false
        return try {
            logBudget.set(LOG_AFTER_CHANGE_N)
            val invoker = xposed.getInvoker(last.method)
            invoker.setType(XposedInterface.Invoker.Type.Chain.FULL)
            invoker.invoke(last.device, *last.args)
            true
        } catch (t: Throwable) {
            // Typically the camera was closed in the meantime. Not fatal.
            xposed.log(Log.WARN, TAG, "resubmit failed", t)
            false
        }
    }

    private fun describe(r: CaptureRequest): String = try {
        "zoom=${r.get(CaptureRequest.CONTROL_ZOOM_RATIO)} " +
            "aeComp=${r.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)} " +
            "af=${r.get(CaptureRequest.CONTROL_AF_MODE)} " +
            "awb=${r.get(CaptureRequest.CONTROL_AWB_MODE)} " +
            "aeLock=${r.get(CaptureRequest.CONTROL_AE_LOCK)}"
    } catch (t: Throwable) {
        "(unreadable: $t)"
    }

    private fun log(priority: Int, msg: String) = xposed.log(priority, TAG, msg)

    companion object {
        private const val TAG = CamOverlayModule.TAG
        private const val LOG_FIRST_N = 20
        private const val LOG_AFTER_CHANGE_N = 3
    }
}
