package com.camoverlay.lab

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.camoverlay.core.camera.CapabilityReader
import com.camoverlay.core.model.CameraCapability

/**
 * A deliberately plain Camera2 app: one camera, one preview surface, one repeating request.
 * It is the controlled target for the CamOverlay hook.
 *
 * The on-screen readout compares what THIS app asked for with what the camera reported in
 * CaptureResult. When the hook overrides a value, the two differ and the line turns orange.
 *
 * "Rebuild every 30 frames" imitates apps that keep re-submitting their own request, which is the
 * case most likely to fight an override.
 *
 * NEW — Camera picker: the old Flip button is replaced by a Spinner that lists every camera the
 * system exposes (front, rear main, ultrawide, telephoto…). Switching cameras also updates the
 * zoom-bubble slider to that camera's supported range.
 *
 * NEW — Picture-in-Picture: pressing Home shrinks CameraLab into a small floating window via the
 * standard Android PiP API. The camera keeps running because the Activity stays on screen in the
 * PiP window. Controls are hidden in PiP and restored when the user returns.
 */
class CameraLabActivity : Activity() {

    // ------------------------------------------------------------------ views
    private lateinit var textureView: TextureView
    private lateinit var hud: TextView
    private lateinit var info: TextView
    private lateinit var zoomBubble: ZoomBubble
    private lateinit var bottomControls: LinearLayout

    // ---------------------------------------------------------------- camera state
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var capability: CameraCapability? = null

    // What this app itself wants. The hook never changes these.
    @Volatile private var appZoom = 1.0f
    @Volatile private var appAeComp = 0
    @Volatile private var rebuildEvery30 = false
    private var frames = 0L

    // ---------------------------------------------------------------- camera picker state
    /** One entry in the camera picker drop-down. */
    private data class CameraEntry(val id: String, val label: String, val capability: CameraCapability)

    /** All cameras the system exposes, in picker order. Populated on first permission grant. */
    private var availableCameras: List<CameraEntry> = emptyList()

    /** Index into [availableCameras] identifying the camera currently open. */
    private var selectedCameraIndex = 0

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart the camera thread (it was stopped in onPause/onStop unless we were in PiP).
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraLab").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startIfReady()
        }
    }

    override fun onPause() {
        // If we are entering PiP the window stays on screen – do NOT close the camera.
        if (!isInPictureInPictureMode) {
            closeCamera()
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        }
        super.onPause()
    }

    override fun onStop() {
        // onStop fires when the PiP window is dismissed by the user (swipe away).
        // At that point we do want to release the camera.
        if (isInPictureInPictureMode) {
            closeCamera()
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        }
        super.onStop()
    }

    /**
     * Android calls this callback whenever PiP state changes.
     * Hide the HUD and controls while in PiP so only the raw preview shows in the tiny window.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        val vis = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        hud.visibility = vis
        info.visibility = vis
        bottomControls.visibility = vis
        if (isInPictureInPictureMode) {
            zoomBubble.hide()
        } else {
            capability?.let { zoomBubble.setCapability(it, appZoom) }
        }
    }

    /**
     * Called when the user presses Home while this Activity is in the foreground.
     * Enter PiP so the camera preview stays alive in a floating window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (device == null) return   // nothing running – normal Home behaviour
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // First time we have permission: build the camera list and populate the spinner.
            enumerateCamerasAndPopulateSpinner()
            startIfReady()
        } else {
            info.text = "Camera permission denied."
        }
    }

    // ================================================================ camera enumeration

    /**
     * Walk every camera ID the system exposes, give each a human-readable label based on
     * LENS_FACING + field-of-view heuristics, then update the picker spinner.
     */
    private fun enumerateCamerasAndPopulateSpinner() {
        val manager = getSystemService(CameraManager::class.java)
        val entries = mutableListOf<CameraEntry>()
        var backCount = 0
        var frontCount = 0

        for (id in manager.cameraIdList) {
            val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            val cap = CapabilityReader.read(id, chars)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val fov = estimateHorizontalFov(chars)

            val label = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> {
                    backCount++
                    when {
                        fov != null && fov > 90f -> "Rear ultrawide (cam $id)"
                        fov != null && fov < 40f -> "Rear telephoto (cam $id)"
                        backCount == 1            -> "Rear main (cam $id)"
                        else                      -> "Rear extra (cam $id)"
                    }
                }
                CameraCharacteristics.LENS_FACING_FRONT -> {
                    frontCount++
                    if (frontCount == 1) "Front (cam $id)" else "Front extra (cam $id)"
                }
                else -> "External (cam $id)"
            }
            entries.add(CameraEntry(id, label, cap))
        }

        availableCameras = entries

        // Populate the spinner on the UI thread (this method may be called from onResume too).
        runOnUiThread {
            val adapter = cameraSpinnerAdapter ?: return@runOnUiThread
            adapter.clear()
            entries.forEach { adapter.add(it.label) }
            adapter.notifyDataSetChanged()
            cameraSpinner?.setSelection(selectedCameraIndex, false)
        }
    }

    /**
     * Rough horizontal field-of-view estimate (degrees) from focal length + physical sensor width.
     * Returns null when the camera does not expose the needed characteristics.
     */
    private fun estimateHorizontalFov(chars: CameraCharacteristics): Float? {
        val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val hFovRad = 2.0 * kotlin.math.atan((physSize.width / (2.0 * fl)))
        return Math.toDegrees(hFovRad).toFloat()
    }

    // ================================================================ camera open / close

    private fun startIfReady() {
        if (device != null || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val handler = cameraHandler ?: return

        // Enumerate cameras if we have not yet (e.g. permission was already granted at cold start).
        if (availableCameras.isEmpty()) enumerateCamerasAndPopulateSpinner()

        val entry = availableCameras.getOrNull(selectedCameraIndex)
        if (entry == null) { info.text = "No camera found."; return }

        val manager = getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(entry.id)
        val cap = entry.capability
        capability = cap
        info.text = describe(cap)
        appZoom = appZoom.coerceIn(cap.zoomMin, cap.zoomMax.coerceAtLeast(cap.zoomMin))
        zoomBubble.setCapability(cap, appZoom)
        Log.i(TAG, "opening ${entry.label}  capabilities: $cap")

        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val size = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.firstOrNull { it.width <= 1920 }
            ?: Size(640, 480)

        try {
            manager.openCamera(entry.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, size)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error"); camera.close(); device = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            info.text = "openCamera failed: $e"
        }
    }

    private fun createSession(camera: CameraDevice, size: Size) {
        val handler = cameraHandler ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)

        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        builder = b

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                { r -> handler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; submit() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "session configure failed")
                    }
                },
            ),
        )
    }

    /** Writes this app's own values into its builder and (re)starts the repeating request. */
    private fun submit() {
        val handler = cameraHandler ?: return
        handler.post {
            val s = session ?: return@post
            val b = builder ?: return@post
            val cap = capability ?: return@post
            try {
                if (cap.zoomSupported)
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, appZoom.coerceIn(cap.zoomMin, cap.zoomMax))
                if (cap.exposureSupported)
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, appAeComp.coerceIn(cap.aeCompMin, cap.aeCompMax))
                s.setRepeatingRequest(b.build(), captureCallback, handler)
            } catch (e: Exception) {
                Log.w(TAG, "setRepeatingRequest failed", e)
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            frames++
            if (rebuildEvery30 && frames % 30 == 0L) submit()
            if (frames % 10 != 0L) return

            val zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
            val ae   = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            val af   = result.get(CaptureResult.CONTROL_AF_MODE)
            val awb  = result.get(CaptureResult.CONTROL_AWB_MODE)
            val overridden = (zoom != null && kotlin.math.abs(zoom - appZoom) > 0.01f) ||
                             (ae   != null && ae != appAeComp)
            val text = buildString {
                append("frame ").append(frames).append('\n')
                append("zoom    app ").append(appZoom).append("   camera ").append(zoom).append('\n')
                append("AE comp app ").append(appAeComp).append("   camera ").append(ae).append('\n')
                append("AF mode ").append(af).append("   AWB mode ").append(awb).append('\n')
                append(if (overridden) "OVERRIDDEN: camera differs from what this app asked for"
                       else            "camera matches this app's request")
            }
            runOnUiThread {
                hud.text = text
                hud.setTextColor(if (overridden) Color.rgb(255, 170, 0) else Color.WHITE)
            }
        }
    }

    private fun closeCamera() {
        try { session?.close(); device?.close() } catch (e: Exception) { Log.w(TAG, "close failed", e) }
        session = null
        device  = null
        builder = null
        zoomBubble.hide()
    }

    // ================================================================ UI

    // Kept as fields so enumerateCamerasAndPopulateSpinner() can reach them after buildUi() returns.
    private var cameraSpinner: Spinner? = null
    private var cameraSpinnerAdapter: ArrayAdapter<String>? = null

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Full-screen preview
        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val pad = (12 * resources.displayMetrics.density).toInt()

        // ── top HUD ──────────────────────────────────────────────────────────
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }
        info = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 11f; typeface = Typeface.MONOSPACE
        }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE
        }
        top.addView(info)
        top.addView(hud)
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // ── bottom controls ───────────────────────────────────────────────────
        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }

        // Row 1: zoom / AE quick buttons
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(Button(this).apply {
            text = "App zoom 1x/2x"
            setOnClickListener {
                appZoom = if (appZoom < 1.5f) 2.0f else 1.0f
                capability?.let { zoomBubble.setCapability(it, appZoom) }
                submit()
            }
        })
        row1.addView(Button(this).apply {
            text = "App AE 0/+2"
            setOnClickListener {
                appAeComp = if (appAeComp == 0) 2 else 0
                submit()
            }
        })
        bottomControls.addView(row1)

        // Row 2: Camera picker  (replaces the old Flip button)
        val pickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        pickerRow.addView(TextView(this).apply {
            text = "Camera: "
            setTextColor(Color.WHITE)
            textSize = 14f
        })

        // Adapter with white text so it is readable on the dark background.
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE)
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.BLACK)
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cameraSpinnerAdapter = adapter

        val spinner = Spinner(this).also { cameraSpinner = it }
        spinner.adapter = adapter

        // A flag to suppress the spurious initial onItemSelected call that Android fires.
        var spinnerReady = false
        spinner.post { spinnerReady = true }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (!spinnerReady || pos == selectedCameraIndex) return
                selectedCameraIndex = pos
                // Reset zoom to new camera's minimum to stay within range.
                appZoom = availableCameras.getOrNull(pos)?.capability?.zoomMin ?: 1f
                closeCamera()
                startIfReady()
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        pickerRow.addView(spinner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bottomControls.addView(pickerRow)

        // Row 3: rebuild-every-30-frames checkbox
        bottomControls.addView(CheckBox(this).apply {
            text = "Rebuild request every 30 frames"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> rebuildEvery30 = checked }
        })

        root.addView(bottomControls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // Floating zoom bubble (above everything else).
        zoomBubble = ZoomBubble(this, root) { zoom ->
            appZoom = zoom
            submit()
        }

        // If permission is already granted at this point, enumerate cameras immediately.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            root.post { enumerateCamerasAndPopulateSpinner() }
        }
        // Otherwise, enumerateCamerasAndPopulateSpinner() is called from onRequestPermissionsResult.

        return root
    }

    // ================================================================ helpers

    private fun describe(c: CameraCapability): String {
        val facing = when (c.lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK  -> "back"
            else -> "external"
        }
        return "camera ${c.cameraId} ($facing)\n" +
            "zoom ${c.zoomMin}..${c.zoomMax}   AE ${c.aeCompMin}..${c.aeCompMax} step ${c.aeCompStep}\n" +
            "AF modes ${c.afModes}   AWB modes ${c.awbModes}   AE lock ${c.aeLockAvailable}"
    }

    companion object {
        private const val TAG = "CameraLab"
        private const val REQ_CAMERA = 1
    }
}
