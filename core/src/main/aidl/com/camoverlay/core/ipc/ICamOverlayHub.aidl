package com.camoverlay.core.ipc;

import com.camoverlay.core.ipc.IHookCallbacks;
import com.camoverlay.core.model.CameraCapability;
import com.camoverlay.core.model.CameraControlStatus;
import com.camoverlay.core.model.SessionInfo;

/** Implemented by the controller app. Called by the hook (inside the target app). */
interface ICamOverlayHub {
    /** Returns false if the pairing secret or the calling package is rejected. */
    boolean register(String secret, in SessionInfo session, IHookCallbacks callbacks);
    oneway void onCapabilities(String sessionId, in CameraCapability capability);
    oneway void onStatus(in CameraControlStatus status);
    oneway void onSessionClosed(String sessionId);
}
