package com.camoverlay.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.camoverlay.core.model.ControlType
import io.github.libxposed.api.XposedInterface

/**
 * Phase 2 control channel, used instead of the overlay UI until the hook is proven:
 *
 *   adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.SET --es type ZOOM --ef value 2.0
 *   adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.CLEAR --es type ZOOM
 *   adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.CLEAR
 *
 * Senders must hold android.permission.DUMP, which adb shell has and ordinary apps do not, so
 * other apps on the phone cannot drive the camera through this receiver.
 */
class DebugReceiver(
    private val xposed: XposedInterface,
    private val state: OverrideState,
    private val interceptor: Camera2Interceptor,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val type = intent.getStringExtra(EXTRA_TYPE)?.let { name ->
                ControlType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
            when (intent.action) {
                ACTION_SET -> {
                    if (type == null || !intent.hasExtra(EXTRA_VALUE)) {
                        log("SET needs --es type <${ControlType.entries.joinToString("|")}> --ef value <number>")
                        return
                    }
                    state.set(type, intent.getFloatExtra(EXTRA_VALUE, 0f))
                }
                ACTION_CLEAR -> if (type == null) state.clearAll() else state.clear(type)
                else -> return
            }
            val resubmitted = interceptor.resubmitRepeating()
            log("${intent.action} type=$type -> overrides=${state.snapshot()} resubmitted=$resubmitted disabled=${state.disabled}")
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, CamOverlayModule.TAG, "debug command failed", t)
        }
    }

    private fun log(msg: String) = xposed.log(Log.INFO, CamOverlayModule.TAG, msg)

    companion object {
        const val ACTION_SET = "com.camoverlay.debug.SET"
        const val ACTION_CLEAR = "com.camoverlay.debug.CLEAR"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VALUE = "value"
        private const val SENDER_PERMISSION = "android.permission.DUMP"

        fun register(context: Context, xposed: XposedInterface, state: OverrideState, interceptor: Camera2Interceptor) {
            val filter = IntentFilter().apply {
                addAction(ACTION_SET)
                addAction(ACTION_CLEAR)
            }
            val receiver = DebugReceiver(xposed, state, interceptor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, null)
            }
        }
    }
}
