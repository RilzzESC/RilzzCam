package com.rilzzcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PORT_DEFAULT = "4747"
        private const val FRAME_INTERVAL_MS = 120L // approx. 8 fps in this starter build
    }

    private lateinit var previewView: PreviewView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView
    private lateinit var captureExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val capturing = AtomicBoolean(false)
    @Volatile private var streaming = false

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
            text = "Android Camera • USB / Wi-Fi"
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
            text = "CONNECT & START"
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
            scaleType = PreviewView.ScaleType.FILL_CENTER
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
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(75)
                    .build()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, imageCapture)
                setStatus("Camera ready")
            } catch (e: Exception) {
                setStatus("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
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
            try {
                val newSocket = Socket(host, port)
                newSocket.tcpNoDelay = true
                val stream = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))
                socket = newSocket
                output = stream
                streaming = true
                runOnUiThread { setStatus("Connected • streaming") }
                handler.post(frameLoop)
            } catch (e: Exception) {
                runOnUiThread { setStatus("Connection failed: ${e.message}") }
                stopStreaming()
            }
        }.start()
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!streaming) return
            val capture = imageCapture
            if (capture == null) {
                handler.postDelayed(this, FRAME_INTERVAL_MS)
                return
            }
            if (capturing.compareAndSet(false, true)) {
                val file = File(cacheDir, "rilzzcam_frame.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(options, captureExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            try {
                                val bytes = file.readBytes()
                                val out = output ?: throw IllegalStateException("No connection")
                                synchronized(out) {
                                    out.writeInt(bytes.size)
                                    out.write(bytes)
                                    out.flush()
                                }
                            } catch (e: Exception) {
                                runOnUiThread { setStatus("Stream stopped: ${e.message}") }
                                stopStreaming()
                            } finally {
                                capturing.set(false)
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            capturing.set(false)
                        }
                    })
            }
            if (streaming) handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun stopStreaming() {
        streaming = false
        handler.removeCallbacks(frameLoop)
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
        runOnUiThread { setStatus("Disconnected") }
    }

    private fun setStatus(message: String) {
        statusText.text = "Status: $message"
    }

    override fun onDestroy() {
        stopStreaming()
        captureExecutor.shutdown()
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
