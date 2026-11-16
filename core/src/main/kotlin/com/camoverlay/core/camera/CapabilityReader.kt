package com.camoverlay.core.camera

import android.hardware.camera2.CameraCharacteristics
import com.camoverlay.core.model.CameraCapability

/**
 * Single source of truth for "what can this camera do". Used by CameraLab (to display) and by the
 * hook (to clamp), so both always agree.
 */
object CapabilityReader {
    fun read(cameraId: String, c: CameraCharacteristics): CameraCapability {
        val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val ae = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        return CameraCapability(
            cameraId = cameraId,
            lensFacing = c.get(CameraCharacteristics.LENS_FACING) ?: -1,
            zoomMin = zoom?.lower ?: 1f,
            zoomMax = zoom?.upper ?: 1f,
            aeCompMin = ae?.lower ?: 0,
            aeCompMax = ae?.upper ?: 0,
            aeCompStep = step?.toFloat() ?: 0f,
            afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty(),
            aeLockAvailable = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
        )
    }
}
