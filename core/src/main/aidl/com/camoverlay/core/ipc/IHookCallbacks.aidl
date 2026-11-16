package com.camoverlay.core.ipc;

import com.camoverlay.core.model.CameraControlCommand;

/** Implemented by the hook (inside the target app). Called by the controller. */
oneway interface IHookCallbacks {
    void apply(in CameraControlCommand command);
    void clearAll(String sessionId);
}
