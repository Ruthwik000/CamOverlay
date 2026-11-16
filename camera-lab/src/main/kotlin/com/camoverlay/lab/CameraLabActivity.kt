package com.camoverlay.lab

import android.Manifest
import android.app.Activity
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
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 */
class CameraLabActivity : Activity() {

    private lateinit var textureView: TextureView
    private lateinit var hud: TextView
    private lateinit var info: TextView

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
    private var useFront = true
    private var frames = 0L

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
        cameraThread = HandlerThread("CameraLab").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startIfReady()
        }
    }

    override fun onPause() {
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startIfReady()
        } else {
            info.text = "Camera permission denied."
        }
    }

    // ---------------------------------------------------------------- camera

    private fun startIfReady() {
        if (device != null || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val handler = cameraHandler ?: return
        val manager = getSystemService(CameraManager::class.java)

        val wanted = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: manager.cameraIdList.firstOrNull()
        if (id == null) {
            info.text = "No camera found."
            return
        }

        val chars = manager.getCameraCharacteristics(id)
        val cap = CapabilityReader.read(id, chars)
        capability = cap
        info.text = describe(cap)
        Log.i(TAG, "capabilities: $cap")

        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val size = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.firstOrNull { it.width <= 1920 }
            ?: Size(640, 480)

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, size)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    camera.close()
                    device = null
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

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            { r -> handler.post(r) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    submit()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "session configure failed")
                }
            },
        )
        camera.createCaptureSession(config)
    }

    /** Writes this app's own values into its builder and (re)starts the repeating request. */
    private fun submit() {
        val handler = cameraHandler ?: return
        handler.post {
            val s = session ?: return@post
            val b = builder ?: return@post
            val cap = capability ?: return@post
            try {
                if (cap.zoomSupported) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, appZoom.coerceIn(cap.zoomMin, cap.zoomMax))
                if (cap.exposureSupported) {
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, appAeComp.coerceIn(cap.aeCompMin, cap.aeCompMax))
                }
                s.setRepeatingRequest(b.build(), captureCallback, handler)
            } catch (e: Exception) {
                // Happens if the camera closed between the post and now.
                Log.w(TAG, "setRepeatingRequest failed", e)
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            frames++
            if (rebuildEvery30 && frames % 30 == 0L) submit()
            if (frames % 10 != 0L) return

            val zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
            val ae = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            val af = result.get(CaptureResult.CONTROL_AF_MODE)
            val awb = result.get(CaptureResult.CONTROL_AWB_MODE)
            val overridden = (zoom != null && kotlin.math.abs(zoom - appZoom) > 0.01f) || (ae != null && ae != appAeComp)
            val text = buildString {
                append("frame ").append(frames).append('\n')
                append("zoom    app ").append(appZoom).append("   camera ").append(zoom).append('\n')
                append("AE comp app ").append(appAeComp).append("   camera ").append(ae).append('\n')
                append("AF mode ").append(af).append("   AWB mode ").append(awb).append('\n')
                append(if (overridden) "OVERRIDDEN: camera differs from what this app asked for" else "camera matches this app's request")
            }
            runOnUiThread {
                hud.text = text
                hud.setTextColor(if (overridden) Color.rgb(255, 170, 0) else Color.WHITE)
            }
        }
    }

    private fun closeCamera() {
        try {
            session?.close()
            device?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
        session = null
        device = null
        builder = null
    }

    // -------------------------------------------------------------------- ui

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Note: the preview is stretched to fill the screen. Aspect-correct preview comes later;
        // it does not matter for reading request/result values.
        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val pad = (12 * resources.displayMetrics.density).toInt()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }
        info = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        top.addView(info)
        top.addView(hud)
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "App zoom 1x/2x"
            setOnClickListener {
                appZoom = if (appZoom < 1.5f) 2.0f else 1.0f
                submit()
            }
        })
        row.addView(Button(this).apply {
            text = "App AE 0/+2"
            setOnClickListener {
                appAeComp = if (appAeComp == 0) 2 else 0
                submit()
            }
        })
        row.addView(Button(this).apply {
            text = "Flip"
            setOnClickListener {
                useFront = !useFront
                closeCamera()
                startIfReady()
            }
        })
        bottom.addView(row)
        bottom.addView(CheckBox(this).apply {
            text = "Rebuild request every 30 frames"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> rebuildEvery30 = checked }
        })
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private fun describe(c: CameraCapability): String {
        val facing = when (c.lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
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
