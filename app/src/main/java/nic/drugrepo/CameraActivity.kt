package nic.drugrepo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import nic.drugrepo.databinding.ActivityCameraBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlinx.coroutines.*

class CameraActivity : Activity() {
    private lateinit var binding: ActivityCameraBinding
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageFile: File? = null
    private var badgeId: String = "DEMO-0001"
    private val scope = MainScope()
    private var countdownJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCapture.isEnabled = false
        badgeId = intent.getStringExtra("badgeId") ?: "DEMO-0001"
        imageFile = File(filesDir, "captured.jpg")
        startCountdown()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            startBackgroundThread()
            if (binding.textureView.isAvailable) {
                openCamera()
            } else {
                binding.textureView.surfaceTextureListener = textureListener
            }
        }

        binding.btnCapture.setOnClickListener {
            captureImage()
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager!!.cameraIdList[0]
            if (checkSelfPermission(Manifest.permission.CAMERA) != PERMISSION_GRANTED) return
            cameraManager!!.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = binding.textureView.surfaceTexture!!
            texture.setDefaultBufferSize(640, 480)
            val surface = Surface(texture)

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            cameraDevice!!.createCaptureSession(Collections.singletonList(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureSession?.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) {
        }
    }

    private fun captureImage() {
        try {
            val cameraId = cameraManager!!.cameraIdList[0]
            val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)
            val jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG)
            val size = jpegSizes[0]
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)

            val surfaces = ArrayList<Surface>()
            surfaces.add(imageReader!!.surface)
            val texture = binding.textureView.surfaceTexture!!
            texture.setDefaultBufferSize(size.width, size.height)
            surfaces.add(Surface(texture))

            val readerListener = ImageReader.OnImageAvailableListener { reader ->
                val image = reader.acquireLatestImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                FileOutputStream(imageFile).use { it.write(bytes) }
                runOnUiThread {
                    val intent = Intent(this@CameraActivity, AnalysisActivity::class.java)
                    intent.putExtra("image", imageFile!!.absolutePath)
                    intent.putExtra("badgeId", badgeId)
                    startActivity(intent)
                    finish()
                }
            }
            imageReader!!.setOnImageAvailableListener(readerListener, backgroundHandler)

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.capture(captureBuilder.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: Exception) {
            Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun startCountdown() {
        binding.tvStatus.text = "Kinetic countdown: wait..."
        countdownJob?.cancel()
        countdownJob = Countdown.start(
            scope = scope,
            seconds = 3,
            onTick = { remaining -> binding.tvStatus.text = "Ready in " + remaining + " s" },
            onFinished = {
                binding.btnCapture.isEnabled = true
                binding.tvStatus.text = "Ready - Tap Capture"
            }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBackgroundThread()
            if (binding.textureView.isAvailable) openCamera() else binding.textureView.surfaceTextureListener = textureListener
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (backgroundThread == null) startBackgroundThread()
    }

    override fun onPause() {
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        scope.cancel()
        cameraDevice?.close()
        imageReader?.close()
        stopBackgroundThread()
        super.onDestroy()
    }
}
