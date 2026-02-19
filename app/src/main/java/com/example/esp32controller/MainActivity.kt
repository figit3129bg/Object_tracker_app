package com.example.esp32controller

import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.ExecutorService
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import android.util.Size
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.camera.core.CameraInfo
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import android.widget.ScrollView
import android.widget.Button
import com.example.esp32controller.OverlayView
import android.hardware.usb.*
import com.hoho.android.usbserial.driver.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs
import android.os.Handler
import android.os.Looper


class MainActivity : AppCompatActivity() {

    // Tracking state
    private var isTracking = false
    private var trackedBox: RectF? = null
    private var lastTrackedTime: Long = 0
    private val TRACKING_TIMEOUT_MS = 300L // Time before giving up on lost object
    private val TRACKING_SIMILARITY_THRESHOLD = 100f // Pixel distance to consider same object
    private val CENTER_DEADZONE = 0.08f


    // Smooth movement control
    private var targetPanValue = 500
    private var targetTiltValue = 500
    private var currentPanValue = 500
    private var currentTiltValue = 500
    private val smoothingFactor = 0.75f // Lower = smoother but slower response
    private val handler = Handler(Looper.getMainLooper())
    private var smoothMovementRunnable: Runnable? = null

    // PID-like control for centering
    private var lastPanError = 0f
    private var lastTiltError = 0f
    private val kP = 3.5f // Proportional gain
    private val kD = 0.8f // Derivative gain (for smoothing)

    // things for object tracking
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var overlay: OverlayView
    private lateinit var tflite: Interpreter
    private var confidenceThreshold: Float = 0.35f
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var maxZoomRatio: Float = 1f
    private var minZoomRatio: Float = 1f
    private val PREFS_NAME = "object_tracker_prefs"
    private val KEY_CONFIDENCE = "confidence_threshold"
    private val KEY_ZOOM = "zoom_ratio"

    val classNames = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane",
        "bus", "train", "truck", "boat", "traffic light", "fire hydrant",
        "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse",
        "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis",
        "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
        "skateboard", "surfboard", "tennis racket", "bottle", "wine glass",
        "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich",
        "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet",
        "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book",
        "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    // Set to store which class indices are currently active
    private val selectedClasses = mutableSetOf<Int>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCameraIfReady()
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    showPermissionRationaleDialog()
                } else {
                    showGoToSettingsDialog()
                }
            }
        }

    private var lastFrameTime = System.currentTimeMillis()
    private var fps = 0

    //things not for object tracking
    private lateinit var serialOutputText: TextView
    private var readThread: Thread? = null
    private lateinit var usbManager: UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private lateinit var panSeekBar: SeekBar
    private lateinit var tiltSeekBar: SeekBar
    private lateinit var panValueText: TextView
    private lateinit var tiltValueText: TextView
    private lateinit var trackingButton: Button
    private lateinit var trackingStatusText: TextView

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "USB_PERMISSION") {
                synchronized(this) {
                    val device: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            openSerialPort(it)
                        }
                    } else {
                        Log.e("USB_SERIAL", "USB permission denied")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("USB_DEBUG", "onCreate() started - app is launching")

        cameraExecutor = Executors.newSingleThreadExecutor()

        val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        confidenceThreshold = sharedPrefs.getFloat(KEY_CONFIDENCE, 0.35f)
        val savedZoom = sharedPrefs.getFloat(KEY_ZOOM, 0f)
        Log.d("USB_DEBUG", "onCreate() started - loading saved preferences")
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        panSeekBar = findViewById(R.id.pan_seekbar)
        tiltSeekBar = findViewById(R.id.tilt_seekbar)
        panValueText = findViewById(R.id.pan_value)
        tiltValueText = findViewById(R.id.tilt_value)
        serialOutputText = findViewById(R.id.serial_output)
        overlay = findViewById(R.id.overlay)
        trackingButton = findViewById(R.id.tracking_button)
        trackingStatusText = findViewById(R.id.tracking_status)

        panSeekBar.max = 1000
        tiltSeekBar.max = 1000
        panSeekBar.progress = 500
        tiltSeekBar.progress = 500

        Log.d("USB_DEBUG", "onCreate() started - loading layout")

        // Tracking button setup
        trackingButton.setOnClickListener {
            isTracking = !isTracking
            if (isTracking) {
                trackingButton.text = "Stop Tracking"
                trackingStatusText.text = "Status: Waiting for object..."
                trackingStatusText.setTextColor(android.graphics.Color.YELLOW)
                startSmoothMovement()
            } else {
                trackingButton.text = "Start Tracking"
                trackingStatusText.text = "Status: Inactive"
                trackingStatusText.setTextColor(android.graphics.Color.GRAY)
                trackedBox = null
                stopSmoothMovement()
                // Reset everything to 500 (stop) and send immediately
                targetPanValue = 500
                targetTiltValue = 500
                currentPanValue = 500
                currentTiltValue = 500
                lastPanError = 0f
                lastTiltError = 0f
                sendCommand("Pan:500\n")
                sendCommand("Tilt:500\n")
            }
        }

        val tiltHomingButton: Button = findViewById(R.id.tilt_homing_Button)
        val panHomingButton: Button = findViewById(R.id.pan_homing_Button)
        val bothHomingButton: Button = findViewById(R.id.pan_tilt_homing_Button)

        val confidenceSlider = findViewById<SeekBar>(R.id.confidenceSlider)
        val confidenceText = findViewById<TextView>(R.id.confidenceText)
        confidenceSlider.progress = ((confidenceThreshold - 0.25f) * 100).toInt()
        confidenceText.text = "Confidence: %.2f".format(confidenceThreshold)

        Log.d("USB_DEBUG", "onCreate() started - confidence calculation")

        confidenceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                confidenceThreshold = 0.25f + (progress / 100f)
                confidenceText.text = "Confidence: %.2f".format(confidenceThreshold)
                sharedPrefs.edit().putFloat(KEY_CONFIDENCE, confidenceThreshold).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val zoomSlider = findViewById<SeekBar>(R.id.zoomSlider)
        zoomSlider.progress = (savedZoom * 100).toInt()

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoomValue = progress / 100f
                cameraControl?.setLinearZoom(zoomValue)
                sharedPrefs.edit().putFloat(KEY_ZOOM, progress / 100f).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val selectButton = findViewById<Button>(R.id.selectClassesButton)
        Log.d("USB_DEBUG", "onCreate() started - loading tracking object button")
        selectButton.setOnClickListener {
            val checkedItems = BooleanArray(classNames.size) { i -> selectedClasses.contains(i) }

            AlertDialog.Builder(this)
                .setTitle("Select classes to detect")
                .setMultiChoiceItems(classNames.toTypedArray(), checkedItems) { _, which, isChecked ->
                    if (isChecked) {
                        selectedClasses.add(which)
                    } else {
                        selectedClasses.remove(which)
                    }
                }
                .setPositiveButton("OK", null)
                .show()
        }

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        tflite = Interpreter(loadModelFile("detect.tflite"), options)
        checkCameraPermissionAndStart()

        Log.d("USB_DEBUG", "onCreate() started - buttons for homing")
        tiltHomingButton.setOnClickListener {
            sendCommand("Homing:1\n")
            Toast.makeText(this, "Tilt Homing sent", Toast.LENGTH_SHORT).show()
        }

        panHomingButton.setOnClickListener {
            sendCommand("Homing:2\n")
            Toast.makeText(this, "Pan Homing sent", Toast.LENGTH_SHORT).show()
        }

        bothHomingButton.setOnClickListener {
            sendCommand("Homing:3\n")
            Toast.makeText(this, "Both Homing sent", Toast.LENGTH_SHORT).show()
        }

        panSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!isTracking) { // Only allow manual control when not tracking
                    panValueText.text = "Pan: $progress"
                    sendCommand("Pan:$progress\n")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        tiltSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!isTracking) { // Only allow manual control when not tracking
                    tiltValueText.text = "Tilt: $progress"
                    sendCommand("Tilt:$progress\n")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val filter = IntentFilter()
        Log.d("USB_DEBUG", "onCreate() started - Register USB receiver")
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(
            usbReceiver,
            IntentFilter("USB_PERMISSION"),
            RECEIVER_NOT_EXPORTED
        )

        connectToUsbDevice()
    }

    private fun startSmoothMovement() {
        smoothMovementRunnable = object : Runnable {
            override fun run() {
                if (isTracking) {
                    // Smoothly interpolate current values toward target
                    currentPanValue += ((targetPanValue - currentPanValue) * smoothingFactor).toInt()
                    currentTiltValue += ((targetTiltValue - currentTiltValue) * smoothingFactor).toInt()

                    // Update UI
                    panValueText.text = "Pan: $currentPanValue"
                    tiltValueText.text = "Tilt: $currentTiltValue"
                    panSeekBar.progress = currentPanValue
                    tiltSeekBar.progress = currentTiltValue

                    // Send to ESP32
                    sendCommand("Pan:$currentPanValue\n")
                    sendCommand("Tilt:$currentTiltValue\n")

                    // Schedule next update (20ms = 50Hz update rate)
                    handler.postDelayed(this, 20)
                }
            }
        }
        handler.post(smoothMovementRunnable!!)
    }

    private fun stopSmoothMovement() {
        smoothMovementRunnable?.let {
            handler.removeCallbacks(it)
        }
    }

    private fun updateTracking(detections: List<DetectionResult>) {
        if (!isTracking) return

        val currentTime = System.currentTimeMillis()

        // Find center of screen
        val screenCenterX = overlay.width / 2f
        val screenCenterY = overlay.height / 2f

        var bestMatch: DetectionResult? = null
        var bestDistance = Float.MAX_VALUE

        // If we have a tracked box, look for similar box
        if (trackedBox != null && (currentTime - lastTrackedTime) < TRACKING_TIMEOUT_MS) {
            val trackedCenterX = trackedBox!!.centerX()
            val trackedCenterY = trackedBox!!.centerY()

            for (detection in detections) {
                val detBox = RectF(
                    detection.left * overlay.width,
                    detection.top * overlay.height,
                    detection.right * overlay.width,
                    detection.bottom * overlay.height
                )

                val distance = calculateDistance(
                    trackedCenterX, trackedCenterY,
                    detBox.centerX(), detBox.centerY()
                )

                if (distance < TRACKING_SIMILARITY_THRESHOLD && distance < bestDistance) {
                    bestDistance = distance
                    bestMatch = detection
                }
            }
        } else {
            // No current track - find box closest to center of screen
            for (detection in detections) {
                val detBox = RectF(
                    detection.left * overlay.width,
                    detection.top * overlay.height,
                    detection.right * overlay.width,
                    detection.bottom * overlay.height
                )

                val distance = calculateDistance(
                    screenCenterX, screenCenterY,
                    detBox.centerX(), detBox.centerY()
                )

                if (distance < bestDistance) {
                    bestDistance = distance
                    bestMatch = detection
                }
            }
        }

        if (bestMatch != null) {
            // Update tracked box
            trackedBox = RectF(
                bestMatch.left * overlay.width,
                bestMatch.top * overlay.height,
                bestMatch.right * overlay.width,
                bestMatch.bottom * overlay.height
            )
            lastTrackedTime = currentTime

            // Calculate error as normalised -1..+1 relative to screen size
            val boxCenterX = trackedBox!!.centerX()
            val boxCenterY = trackedBox!!.centerY()

            // panError:  positive = object is RIGHT of center
            // tiltError: positive = object is BELOW center
            var panError  = (boxCenterX - screenCenterX) / screenCenterX
            var tiltError = (boxCenterY - screenCenterY) / screenCenterY

                // ---------- DEAD ZONE ----------
            // If object is close enough to center → STOP motors
            if (abs(panError) < CENTER_DEADZONE) {
                panError = 0f
            }
            if (abs(tiltError) < CENTER_DEADZONE) {
                tiltError = 0f
            }
            // If fully inside dead zone → hard stop
            if (panError == 0f && tiltError == 0f) {
                targetPanValue = 500
                targetTiltValue = 500
                lastPanError = 0f
                lastTiltError = 0f
                return
            }

            val panCorrection  = kP * panError  + kD * (panError  - lastPanError)
            val tiltCorrection = kP * tiltError + kD * (tiltError - lastTiltError)

            lastPanError  = panError
            lastTiltError = tiltError

            // ESP32 is a SPEED STICK: 500 = stop, >500 = move, <500 = move opposite.
            // Max stick range we allow is ±250 around 500 (so 250..750).
            //
            // Pan:  object RIGHT (panError>0) → need to pan RIGHT → send >500  → 500 + correction
            // Tilt: object BELOW (tiltError>0) → need to tilt DOWN → but ESP32
            //       delta>0 means UP, so DOWN = <500  → 500 - correction
            val maxStick = 250f
            targetPanValue  = (500f + panCorrection  * maxStick).toInt().coerceIn(250, 750)
            targetTiltValue = (500f + tiltCorrection * maxStick).toInt().coerceIn(250, 750)

            // Update status
            val className = if (bestMatch.classIndex in classNames.indices)
                classNames[bestMatch.classIndex] else "?"
            trackingStatusText.text = "Tracking: $className (${String.format("%.0f", bestMatch.score * 100)}%)"
            trackingStatusText.setTextColor(android.graphics.Color.GREEN)

            Log.d("TRACKING", "Error: Pan=$panError, Tilt=$tiltError | Target: Pan=$targetPanValue, Tilt=$targetTiltValue")

        } else if ((currentTime - lastTrackedTime) > TRACKING_TIMEOUT_MS) {
            // Lost track — tell the motors to stop
            trackedBox = null
            targetPanValue = 500
            targetTiltValue = 500
            lastPanError = 0f
            lastTiltError = 0f
            trackingStatusText.text = "Status: Searching..."
            trackingStatusText.setTextColor(android.graphics.Color.YELLOW)
            Log.d("TRACKING", "Lost object - searching")
        }
    }

    private fun calculateDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val channel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSmoothMovement()
        unregisterReceiver(usbReceiver)
        disconnectUsb()
        cameraExecutor.shutdown()
    }

    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> {
                startCameraIfReady()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionRationaleDialog()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera required")
            .setMessage("This app needs camera access to track objects. Please allow camera permission.")
            .setPositiveButton("Allow") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera permission denied")
            .setMessage("Camera permission was permanently denied. Open app settings to enable it.")
            .setPositiveButton("Open settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToUsbDevice() {
        Log.d("USB_SERIAL", "Trying to connect...")
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.e("USB_SERIAL", "No drivers found!")
            Toast.makeText(this, "No USB device found", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = availableDrivers[0]
        Log.d("USB_SERIAL", "Found driver: ${driver.device.deviceName}")

        val connection = usbManager.openDevice(driver.device) ?: run {
            Log.w("USB_SERIAL", "Cannot open device - requesting permission")
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent("USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(driver.device, permissionIntent)
            Toast.makeText(this, "Requesting USB permission", Toast.LENGTH_SHORT).show()
            return
        }

        usbSerialPort = driver.ports[0]
        Log.d("USB_SERIAL", "Port opened: ${usbSerialPort != null}")

        try {
            usbSerialPort = driver.ports[0]
            usbSerialPort!!.open(connection)
            usbSerialPort!!.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            startSerialReading()
            Log.d("USB_SERIAL", "Port configured at 115200 baud")
            Toast.makeText(this, "USB connected", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("USB_SERIAL", "Open/config failed: ${e.message}")
            Toast.makeText(this, "Error opening USB: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSerialReading() {
        readThread = Thread {
            val buffer = ByteArray(1024)

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val len = usbSerialPort?.read(buffer, 1000) ?: -1

                    if (len > 0) {
                        val text = String(buffer, 0, len)
                        runOnUiThread {
                            serialOutputText.append(text)
                        }
                    }

                } catch (e: IOException) {
                    Log.e("USB_SERIAL", "Read failed", e)
                    break
                }
            }
        }
        readThread!!.start()
    }

    private fun openSerialPort(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: run {
                Log.e("USB_SERIAL", "No driver for device")
                return
            }

        val connection = usbManager.openDevice(device)
            ?: run {
                Log.e("USB_SERIAL", "Cannot open device")
                return
            }

        usbSerialPort = driver.ports[0]

        try {
            usbSerialPort!!.open(connection)
            usbSerialPort!!.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            Log.d("USB_SERIAL", "Serial port opened")
            startSerialReading()

        } catch (e: IOException) {
            Log.e("USB_SERIAL", "Open/config failed", e)
        }
    }

    private fun startCameraIfReady() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(300, 300))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    var bitmap = imageProxy.toBitmap()

                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix()
                            .apply { postRotate(rotationDegrees.toFloat()) }
                        bitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true
                        )
                    }

                    val detections = runModel(bitmap)

                    val filtered = if (selectedClasses.isEmpty()) {
                        detections.filter { it.score >= confidenceThreshold }
                    } else {
                        detections.filter {
                            it.score >= confidenceThreshold && selectedClasses.contains(
                                it.classIndex
                            )
                        }
                    }

                    // Update tracking logic
                    updateTracking(filtered)

                    val currentTime = System.currentTimeMillis()
                    val delta = currentTime - lastFrameTime
                    if (delta > 0) {
                        fps = (1000f / delta).toInt()
                    }
                    lastFrameTime = currentTime

                    overlay.post {
                        if (overlay.width == 0 || overlay.height == 0) return@post

                        val rects = filtered.map { d ->
                            RectF(
                                (d.left * overlay.width).coerceIn(0f, overlay.width.toFloat()),
                                (d.top * overlay.height).coerceIn(0f, overlay.height.toFloat()),
                                (d.right * overlay.width).coerceIn(0f, overlay.width.toFloat()),
                                (d.bottom * overlay.height).coerceIn(
                                    0f,
                                    overlay.height.toFloat()
                                )
                            )
                        }

                        val labels = filtered.map { d ->
                            val className =
                                if (d.classIndex in classNames.indices) classNames[d.classIndex] else "?"
                            "$className %.2f".format(d.score)
                        }

                        val classes = filtered.map { it.classIndex }

                        overlay.setBoxes(rects, labels, classes, fps, trackedBox)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                maxZoomRatio = cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                minZoomRatio = cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

            } catch (exc: Exception) {
                exc.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    data class DetectionResult(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val classIndex: Int,
        val score: Float
    )

    fun runModel(bitmap: Bitmap): List<DetectionResult> {
        val inputBitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, true)

        val inputBuffer = ByteBuffer.allocateDirect(300 * 300 * 3)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        for (y in 0 until 300) {
            for (x in 0 until 300) {
                val pixel = inputBitmap.getPixel(x, y)
                inputBuffer.put((pixel shr 16 and 0xFF).toByte())
                inputBuffer.put((pixel shr 8 and 0xFF).toByte())
                inputBuffer.put((pixel and 0xFF).toByte())
            }
        }
        inputBuffer.rewind()

        val outputLocations = Array(1) { Array(10) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(10) }
        val outputScores = Array(1) { FloatArray(10) }
        val numDetections = FloatArray(1)

        val outputMap = mapOf(
            0 to outputLocations,
            1 to outputClasses,
            2 to outputScores,
            3 to numDetections
        )

        tflite.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        val results = mutableListOf<DetectionResult>()
        val count = numDetections[0].toInt().coerceAtMost(10)

        for (i in 0 until count) {
            val score = outputScores[0][i]
            if (score < confidenceThreshold) continue

            val ymin = outputLocations[0][i][0]
            val xmin = outputLocations[0][i][1]
            val ymax = outputLocations[0][i][2]
            val xmax = outputLocations[0][i][3]

            results.add(
                DetectionResult(
                    left = xmin,
                    top = ymin,
                    right = xmax,
                    bottom = ymax,
                    classIndex = outputClasses[0][i].toInt(),
                    score = score
                )
            )
        }

        return results
    }

    private fun disconnectUsb() {
        readThread?.interrupt()
        usbSerialPort?.close()
        usbSerialPort = null
        Toast.makeText(this, "USB disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun sendCommand(command: String) {
        usbSerialPort?.let {
            try {
                it.write(command.toByteArray(), 1000)
            } catch (e: IOException) {
                Toast.makeText(this, "Error sending: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}