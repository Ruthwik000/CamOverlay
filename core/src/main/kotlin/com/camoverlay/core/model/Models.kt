package com.camoverlay.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CameraControlCommand(
    val sessionId: String,
    val packageName: String,
    /** Monotonic per control type. The hook drops anything older than the last applied one. */
    val seq: Long,
    val type: ControlType,
    val value: Float,
    /** true = remove this override and fall back to the app's own value. */
    val clear: Boolean = false,
) : Parcelable

@Parcelize
data class CameraControlStatus(
    val sessionId: String,
    val type: ControlType,
    val seq: Long,
    val supported: Boolean,
    val applied: Boolean,
    /** The value actually written after clamping. */
    val appliedValue: Float,
    val message: String? = null,
) : Parcelable

@Parcelize
data class SessionInfo(
    val sessionId: String,
    val packageName: String,
    val cameraId: String,
) : Parcelable

@Parcelize
data class CameraCapability(
    val cameraId: String,
    val lensFacing: Int,
    val zoomMin: Float,
    val zoomMax: Float,
    val aeCompMin: Int,
    val aeCompMax: Int,
    /** EV per step, e.g. 0.1667. */
    val aeCompStep: Float,
    val afModes: List<Int>,
    val awbModes: List<Int>,
    val aeLockAvailable: Boolean,
) : Parcelable {
    val zoomSupported: Boolean get() = zoomMax > zoomMin
    val exposureSupported: Boolean get() = aeCompMax > aeCompMin
}
