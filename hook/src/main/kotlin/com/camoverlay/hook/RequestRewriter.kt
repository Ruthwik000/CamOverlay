package com.camoverlay.hook

import android.hardware.camera2.CaptureRequest
import com.camoverlay.core.camera.ControlClamp
import com.camoverlay.core.model.CameraCapability
import com.camoverlay.core.model.ControlType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Makes a modified copy of a CaptureRequest. The app's own request object is never changed, which
 * is what makes "restore" trivial: stop rewriting and the app's values are back.
 *
 * Uses three framework internals (AOSP names). They are resolved once; if any is missing on this
 * Android build, [available] is false and nothing is rewritten.
 *  - private CaptureRequest(CaptureRequest source)        copy constructor used by Builder.build()
 *  - CaptureRequest.mLogicalCameraSettings                 the CameraMetadataNative settings
 *  - CameraMetadataNative.set(CaptureRequest.Key, Object)
 */
class RequestRewriter {
    private var copyCtor: Constructor<CaptureRequest>? = null
    private var settingsField: Field? = null
    private var setMethod: Method? = null

    var unavailableReason: String? = null
        private set
    val available: Boolean get() = unavailableReason == null

    init {
        try {
            val ctor = CaptureRequest::class.java.getDeclaredConstructor(CaptureRequest::class.java)
            ctor.isAccessible = true
            val field = CaptureRequest::class.java.getDeclaredField("mLogicalCameraSettings")
            field.isAccessible = true
            setMethod = field.type.getMethod("set", CaptureRequest.Key::class.java, Any::class.java)
            copyCtor = ctor
            settingsField = field
        } catch (t: Throwable) {
            unavailableReason = t.toString()
        }
    }

    /**
     * @return the rewritten copy, or the original instance if no override ended up applying.
     * @throws Throwable if the copy fails; the caller treats that as "disable CamOverlay".
     */
    fun rewrite(original: CaptureRequest, overrides: Map<ControlType, Float>, cap: CameraCapability): CaptureRequest {
        val writes = ArrayList<Pair<CaptureRequest.Key<*>, Any>>(overrides.size)
        for ((type, raw) in overrides) {
            val v = ControlClamp.clamp(type, raw, cap) ?: continue
            when (type) {
                ControlType.ZOOM -> writes += CaptureRequest.CONTROL_ZOOM_RATIO to v
                ControlType.EXPOSURE -> writes += CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION to v.roundToInt()
                ControlType.FOCUS -> writes += CaptureRequest.CONTROL_AF_MODE to v.roundToInt()
                ControlType.WHITE_BALANCE -> writes += CaptureRequest.CONTROL_AWB_MODE to v.roundToInt()
                ControlType.AE_LOCK -> writes += CaptureRequest.CONTROL_AE_LOCK to (v >= 0.5f)
                ControlType.AF_LOCK -> Unit
            }
        }
        if (writes.isEmpty()) return original

        val copy = copyCtor!!.newInstance(original)
        val settings = settingsField!!.get(copy)
        for ((key, value) in writes) setMethod!!.invoke(settings, key, value)
        return copy
    }
}
