package com.camoverlay.hook

import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import com.camoverlay.core.camera.CapabilityReader
import com.camoverlay.core.model.CameraCapability
import com.camoverlay.core.model.ControlType
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** The app's most recent repeating submission, exactly as the app made it. */
class RepeatingSubmission(val device: Any, val method: Method, val args: Array<Any?>)

/**
 * Per-process state: which controls the user is overriding, and what the app last asked for.
 * Only controls present in [overrides] are ever touched; everything else passes through.
 */
class OverrideState {
    private val overrides = ConcurrentHashMap<ControlType, Float>()
    private val capabilities = ConcurrentHashMap<String, CameraCapability>()

    @Volatile private var context: Context? = null

    /** Set when a hook throws. From then on every request passes through untouched. */
    @Volatile var disabled: Boolean = false
        private set

    @Volatile var lastRepeating: RepeatingSubmission? = null

    /** Returns true the first time a context is attached. */
    fun attachContext(ctx: Context): Boolean {
        if (context != null) return false
        context = ctx.applicationContext ?: ctx
        return true
    }

    fun set(type: ControlType, value: Float) { overrides[type] = value }
    fun clear(type: ControlType) { overrides.remove(type) }
    fun clearAll() { overrides.clear() }

    /** Empty when nothing is overridden or the hook has disabled itself. */
    fun snapshot(): Map<ControlType, Float> = if (disabled || overrides.isEmpty()) emptyMap() else HashMap(overrides)

    fun disable() {
        disabled = true
        overrides.clear()
    }

    fun onDeviceClosed(device: Any?) {
        if (lastRepeating?.device === device) lastRepeating = null
    }

    fun capabilityFor(device: Any?): CameraCapability? {
        val id = (device as? CameraDevice)?.id ?: return null
        capabilities[id]?.let { return it }
        val manager = context?.getSystemService(CameraManager::class.java) ?: return null
        val cap = CapabilityReader.read(id, manager.getCameraCharacteristics(id))
        capabilities[id] = cap
        return cap
    }
}
