package com.camoverlay.core.model

enum class ControlType {
    /** Zoom ratio, e.g. 2.0 = 2x. */
    ZOOM,

    /** Auto-exposure compensation, in device steps (integer carried as Float). */
    EXPOSURE,

    /** A CaptureRequest.CONTROL_AF_MODE_* constant. */
    FOCUS,

    /** A CaptureRequest.CONTROL_AWB_MODE_* constant. */
    WHITE_BALANCE,

    /** 1 = locked, 0 = unlocked. */
    AE_LOCK,

    /** Not implemented yet: needs an AF trigger sequence, not a single key. */
    AF_LOCK,
}
