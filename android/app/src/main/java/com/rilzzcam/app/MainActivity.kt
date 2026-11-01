package com.rilzzcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.util.Range
import android.hardware.camera2.CaptureRequest
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PORT_DEFAULT = "4747"
        private const val TARGET_FPS = 60
        private const val FRAME_INTERVAL_MS = 16L // cap sender near 60 fps
        private const val JPEG_QUALITY = 65
        private const val FRAME_WIDTH = 1920
        private const val FRAME_HEIGHT = 1080
    }

    private lateinit var previewView: PreviewView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView
    private lateinit var captureExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var streaming = false
    private val latestFrame = AtomicReference<ByteArray?>(null)
    @Volatile private var lastEncodedAt = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else setStatus("Izin kamera diperlukan.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xFF10111A.toInt())
        }
        val title = TextView(this).apply {
            text = "RILZZCAM"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Camera streaming • USB / Wi-Fi • Full HD 1080p • target up to 60 FPS"
            textSize = 13f
            setTextColor(0xFFB9B8C7.toInt())
        }
        hostInput = EditText(this).apply {
            setSingleLine(true)
            hint = "IP PC (USB: 127.0.0.1)"
            setText("127.0.0.1")
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF999999.toInt())
        }
        portInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Port"
            setText(PORT_DEFAULT)
            inputType = 2
            setTextColor(0xFFFFFFFF.toInt())
        }
        val connectButton = Button(this).apply {
            text = "CONNECT & START / STOP"
            setOnClickListener { if (streaming) stopStreaming() else connectAndStart() }
        }
        val switchButton = Button(this).apply {
            text = "SWITCH FRONT / BACK CAMERA"
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startCamera()
            }
        }
        statusText = TextView(this).apply {
            text = "Status: Camera ready"
            textSize = 14f
            setTextColor(0xFFB9F6CA.toInt())
        }
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            // Fit the complete camera frame instead of cropping its edges.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(hostInput)
        root.addView(portInput)
        root.addView(connectButton)
        root.addView(switchButton)
        root.addView(statusText)
        root.addView(previewView)
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                // Request 60 FPS only when the selected camera advertises a compatible AE range.
                // CameraX may still deliver fewer frames at 1080p depending on sensor/stream limits.
                val supportedRanges = provider.getCameraInfo(selector).getSupportedFrameRateRanges()
                val fpsRange = supportedRanges.firstOrNull { it.lower == TARGET_FPS && it.upper == TARGET_FPS }
                    ?: supportedRanges
                        .filter { it.lower <= TARGET_FPS && it.upper >= TARGET_FPS }
                        .maxByOrNull { it.lower }
                    ?: supportedRanges.maxByOrNull { it.upper }

                val previewBuilder = Preview.Builder()
                if (fpsRange != null) previewBuilder.setTargetFrameRate(fpsRange)
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysisBuilder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(FRAME_WIDTH, FRAME_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (fpsRange != null) {
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
                    )
                }
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(captureExecutor) { image -> analyzeFrame(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                imageAnalysis = analysis
                val fpsLabel = fpsRange?.let { "${it.lower}-${it.upper} FPS" } ?: "camera default FPS"
                setStatus("Camera ready • target 60 FPS • camera range $fpsLabel • FHD requested")
            } catch (e: Exception) {
                setStatus("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (!streaming) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastEncodedAt < FRAME_INTERVAL_MS) return
            lastEncodedAt = now
            val jpeg = imageToJpeg(image, JPEG_QUALITY)
            if (jpeg != null) latestFrame.set(jpeg)
        } catch (e: Exception) {
            runOnUiThread { setStatus("Frame error: ${e.message}") }
        } finally {
            image.close()
        }
    }

    // Convert CameraX YUV_420_888 planes to NV21, then encode a small JPEG frame.
    private fun imageToJpeg(image: ImageProxy, quality: Int): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val nv21 = ByteArray(width * height + width * height / 2)
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        var out = 0

        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[out++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (col in 0 until width / 2) {
                nv21[out++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                nv21[out++] = uBuffer.get(uRow + col * uPlane.pixelStride)
            }
        }

        val jpegStream = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val ok = yuv.compressToJpeg(Rect(0, 0, width, height), quality, jpegStream)
        return if (ok) jpegStream.toByteArray() else null
    }

    private fun connectAndStart() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 4747
        if (host.isBlank() || port !in 1..65535) {
            setStatus("Periksa alamat IP dan port.")
            return
        }
        setStatus("Connecting to $host:$port ...")
        Thread {
            var newSocket: Socket? = null
            try {
                newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.sendBufferSize = 256 * 1024
                newSocket.connect(InetSocketAddress(host, port), 5000)
                socket = newSocket
                output = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream(), 64 * 1024))
                latestFrame.set(null)
                lastEncodedAt = 0L
                streaming = true
                runOnUiThread { setStatus("Connected • FHD 1920×1080 • target up to 60 FPS") }
                sendFrames()
            } catch (e: Exception) {
                streaming = false
                try { newSocket?.close() } catch (_: Exception) {}
                socket = null
                output = null
                runOnUiThread { setStatus("Connection failed: ${e.message}") }
            }
        }.start()
    }

    // Send only the newest encoded frame. Old frames are replaced, not queued.
    private fun sendFrames() {
        try {
            while (streaming) {
                val frame = latestFrame.getAndSet(null)
                if (frame == null) {
                    Thread.sleep(3)
                    continue
                }
                val out = output ?: throw IllegalStateException("No connection")
                out.writeInt(frame.size) // DataOutputStream writes big-endian length
                out.write(frame)
                out.flush()
            }
        } catch (e: Exception) {
            runOnUiThread { setStatus("Stream stopped: ${e.message}") }
        } finally {
            stopStreaming()
        }
    }

    private fun stopStreaming() {
        streaming = false
        latestFrame.set(null)
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
        runOnUiThread { setStatus("Disconnected") }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusText.text = "Status: $message" }
    }

    override fun onDestroy() {
        stopStreaming()
        imageAnalysis?.clearAnalyzer()
        captureExecutor.shutdown()
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
