package com.camoverlay.core.camera

import com.camoverlay.core.model.CameraCapability
import com.camoverlay.core.model.ControlType
import kotlin.math.roundToInt

/**
 * Turns a requested value into one the camera supports, or null if the control is not supported.
 * This is the safety boundary: the hook calls it right before writing a key.
 */
object ControlClamp {
    fun clamp(type: ControlType, value: Float, cap: CameraCapability): Float? = when (type) {
        ControlType.ZOOM ->
            if (cap.zoomSupported && value.isFinite()) value.coerceIn(cap.zoomMin, cap.zoomMax) else null

        ControlType.EXPOSURE ->
            if (cap.exposureSupported && value.isFinite()) {
                value.roundToInt().coerceIn(cap.aeCompMin, cap.aeCompMax).toFloat()
            } else null

        ControlType.FOCUS -> value.takeIf { it.isFinite() && it.roundToInt() in cap.afModes }

        ControlType.WHITE_BALANCE -> value.takeIf { it.isFinite() && it.roundToInt() in cap.awbModes }

        ControlType.AE_LOCK -> if (cap.aeLockAvailable) (if (value >= 0.5f) 1f else 0f) else null

        ControlType.AF_LOCK -> null
    }
}
