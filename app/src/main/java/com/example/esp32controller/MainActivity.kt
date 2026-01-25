package com.example.esp32controller

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
import android.util.Log
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
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import android.net.wifi.WifiManager
import android.text.format.Formatter


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
import com.example.esp32controller.R
import android.widget.ScrollView   // For ScrollView class
import android.view.View
import android.widget.Button
import com.example.objecttrackerapp.OverlayView

class MainActivity : AppCompatActivity() {

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
    private val selectedClasses = mutableSetOf<Int>() // e.g., initially empty or default to all

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted — start camera
               startCameraIfReady()
            } else {
                // Permission denied — show rationale or direct to settings
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                   showPermissionRationaleDialog()
                } else {
                    // User permanently denied (Don't ask again) — guide them to settings
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


    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                connectToUsbDevice()
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                disconnectUsb()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        cameraExecutor = Executors.newSingleThreadExecutor()

        val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Load saved confidence, default to 0.35
        confidenceThreshold = sharedPrefs.getFloat(KEY_CONFIDENCE, 0.35f)
        // Load saved zoom, default to 0f (slider 0%)
        val savedZoom = sharedPrefs.getFloat(KEY_ZOOM, 0f)

        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        panSeekBar = findViewById(R.id.pan_seekbar)
        tiltSeekBar = findViewById(R.id.tilt_seekbar)
        panValueText = findViewById(R.id.pan_value)
        tiltValueText = findViewById(R.id.tilt_value)
        serialOutputText = findViewById(R.id.serial_output)

        overlay = findViewById(R.id.overlay)

        panSeekBar.max = 1000
        tiltSeekBar.max = 1000
        panSeekBar.progress = 500
        tiltSeekBar.progress = 500


        val tiltHomingButton: Button = findViewById(R.id.tilt_homing_Button)
        val panHomingButton: Button = findViewById(R.id.pan_homing_Button)
        val bothHomingButton: Button = findViewById(R.id.pan_tilt_homing_Button)
        // slider setup
        val confidenceSlider = findViewById<SeekBar>(R.id.confidenceSlider)
        val confidenceText = findViewById<TextView>(R.id.confidenceText)
        confidenceSlider.progress = ((confidenceThreshold - 0.25f) * 100).toInt()
        confidenceText.text = "Confidence: %.2f".format(confidenceThreshold)
        confidenceSlider.progress = ((confidenceThreshold - 0.25f) * 100).toInt()
        confidenceText.text = "Confidence: %.2f".format(confidenceThreshold)

        confidenceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                confidenceThreshold = 0.25f + (progress / 100f)
                confidenceText.text = "Confidence: %.2f".format(confidenceThreshold)

                // Save
                sharedPrefs.edit().putFloat(KEY_CONFIDENCE, confidenceThreshold).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        val zoomSlider = findViewById<SeekBar>(R.id.zoomSlider)


        zoomSlider.progress = (savedZoom * 100).toInt()

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map progress 0..100 to linear zoom 0.0..1.0
                val zoomValue = progress / 100f
                cameraControl?.setLinearZoom(zoomValue)
                // Save
                sharedPrefs.edit().putFloat(KEY_ZOOM, progress / 100f).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        val selectButton = findViewById<Button>(R.id.selectClassesButton)

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
            setNumThreads(6)
        }
        tflite = Interpreter(loadModelFile("detect.tflite"), options)
        checkCameraPermissionAndStart()




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
                panValueText.text = "Pan: $progress"
                sendCommand("PAN:$progress\n")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        tiltSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tiltValueText.text = "Tilt: $progress"
                sendCommand("TILT:$progress\n")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Register USB receiver
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbReceiver, filter)

        // Check for already connected device
        connectToUsbDevice()
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
        unregisterReceiver(usbReceiver)
        disconnectUsb()
        cameraExecutor.shutdown()
    }
    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> {
                // Already granted
                startCameraIfReady()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show an in-app rationale before requesting permission again
                showPermissionRationaleDialog()
            }

            else -> {
                // Directly request permission (first time)
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


    // Called only when permission is granted
    private fun startCameraIfReady() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Build Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }

            // Build ImageAnalysis
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(300, 300)) // must match your model input
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()





            // Set analyzer
            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->


                try {
                    var bitmap = imageProxy.toBitmap()

                    // Rotate bitmap if needed
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

                    // Run object detection
                    val detections = runModel(bitmap)

                    // Filter detections by confidence + selected classes
                    val filtered = if (selectedClasses.isEmpty()) {
                        detections.filter { it.score >= confidenceThreshold }
                    } else {
                        detections.filter {
                            it.score >= confidenceThreshold && selectedClasses.contains(
                                it.classIndex
                            )
                        }
                    }

                    // FPS calculation
                    val currentTime = System.currentTimeMillis()
                    val delta = currentTime - lastFrameTime
                    if (delta > 0) {
                        fps = (1000f / delta).toInt()
                    }
                    lastFrameTime = currentTime

                    // Draw overlay
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

                        //  send rects, labels, fps, and classes
                        overlay.setBoxes(rects, labels, classes, fps)


                        /*        val currentTime = System.currentTimeMillis()
                    if (detections.isNotEmpty() && currentTime - lastLogTime >= 1000) { // 1000 ms = 1 sec
                        val d = detections[0]
                        Log.d(
                            "DEBUG_DET",
                            "norm: ${d.left},${d.top},${d.right},${d.bottom}  " +
                                    "scaled: ${d.left*overlay.width},${d.top*overlay.height}"
                        )
                        lastLogTime = currentTime
                    }

                    */

                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    imageProxy.close()
                }

            }

            // Camera selector
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind previous use cases
                cameraProvider.unbindAll()

                // Bind preview + analyzer once
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                // Store CameraControl and CameraInfo for zoom
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                maxZoomRatio = cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                minZoomRatio = cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

            } catch (exc: Exception) {
                exc.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }
    private fun analyzeImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()  // <-- converts frame to Bitmap
        // Next step: send this bitmap to TFLite model
        processImage(bitmap)
        imageProxy.close()                  // important: release the frame
    }

    private fun ImageProxy. toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

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

    fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val inputSize = 300
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4) // 4 bytes per float
        inputBuffer.order(ByteOrder.nativeOrder())

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        var pixelIndex = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val pixel = intValues[pixelIndex++]
                // Convert from ARGB to RGB and normalize
                inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255f)) // R
                inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255f))  // G
                inputBuffer.putFloat(((pixel and 0xFF) / 255f))        // B
            }
        }
        inputBuffer.rewind()
        return inputBuffer
    }

    fun processImage(bitmap: Bitmap) {
        // Run the TFLite model
        val results = runModel(bitmap)

        // Convert results to RectF objects
        val detectedBoxes = mutableListOf<RectF>()
        for (result in results) {
            val left = result.left
            val top = result.top
            val right = result.right
            val bottom = result.bottom
            detectedBoxes.add(RectF(left, top, right, bottom))
        }

        //  Send boxes to the overlay
        overlay.setBoxes(detectedBoxes)
    }

    data class DetectionResult(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val classIndex: Int,
        val score: Float
    )
    fun runModel(bitmap: Bitmap): List<com.example.esp32controller.MainActivity.DetectionResult> {
        // Model input: we assume model expects 300x300 RGB uint8
        val inputBitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, true)

        val inputBuffer = ByteBuffer.allocateDirect(300 * 300 * 3)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        for (y in 0 until 300) {
            for (x in 0 until 300) {
                val pixel = inputBitmap.getPixel(x, y)
                inputBuffer.put((pixel shr 16 and 0xFF).toByte()) // R
                inputBuffer.put((pixel shr 8 and 0xFF).toByte())  // G
                inputBuffer.put((pixel and 0xFF).toByte())        // B
            }
        }
        inputBuffer.rewind()

        // Prepare outputs (as before)
        val outputLocations = Array(1) { Array(10) { FloatArray(4) } } // normalized: [ymin, xmin, ymax, xmax]
        val outputClasses = Array(1) { FloatArray(10) }
        val outputScores = Array(1) { FloatArray(10) }
        val numDetections = FloatArray(1)

        val outputMap = mapOf(
            0 to outputLocations,
            1 to outputClasses,
            2 to outputScores,
            3 to numDetections
        )

        // Run interpreter
        tflite.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        // Convert outputs to normalized DetectionResult (values 0..1)
        val results = mutableListOf<com.example.esp32controller.MainActivity.DetectionResult>()
        val count = numDetections[0].toInt().coerceAtMost(10)

        for (i in 0 until count) {
            val score = outputScores[0][i]
            if (score < confidenceThreshold ) continue

            val ymin = outputLocations[0][i][0]     // normalized
            val xmin = outputLocations[0][i][1]     // normalized
            val ymax = outputLocations[0][i][2]     // normalized
            val xmax = outputLocations[0][i][3]     // normalized

            results.add(
                DetectionResult(
                    left = xmin,   // normalized x
                    top = ymin,    // normalized y
                    right = xmax,
                    bottom = ymax,
                    classIndex = outputClasses[0][i].toInt(),
                    score = score
                )
            )
        }

        return results
    }

    private fun scaleDetectionsToOverlay(
        detections: List<com.example.esp32controller.MainActivity.DetectionResult>,
        overlay: OverlayView,
        originalBitmapWidth: Int,
        originalBitmapHeight: Int
    ): List<RectF> {
        return detections.map { detection ->
            val scaledLeft = detection.left * overlay.width.toFloat() / originalBitmapWidth
            val scaledTop = detection.top * overlay.height.toFloat() / originalBitmapHeight
            val scaledRight = detection.right * overlay.width.toFloat() / originalBitmapWidth
            val scaledBottom = detection.bottom * overlay.height.toFloat() / originalBitmapHeight

            // Clamp to overlay bounds (prevents boxes going off-screen)
            RectF(
                scaledLeft.coerceIn(0f, overlay.width.toFloat()),
                scaledTop.coerceIn(0f, overlay.height.toFloat()),
                scaledRight.coerceIn(0f, overlay.width.toFloat()),
                scaledBottom.coerceIn(0f, overlay.height.toFloat())
            )
        }
    }

    private fun runObjectDetection(bitmap: Bitmap) {
        val input = bitmapToByteBuffer(bitmap)

        // Output arrays (SSD MobileNet V2 typical output)
        val locations = Array(1) { Array(10) { FloatArray(4) } } // 10 boxes, each 4 floats
        val classes = Array(1) { FloatArray(10) } // 10 classes
        val scores = Array(1) { FloatArray(10) } // 10 scores
        val numDetections = FloatArray(1)

        val outputMap = mapOf(
            0 to locations,
            1 to classes,
            2 to scores,
            3 to numDetections
        )

        tflite.runForMultipleInputsOutputs(arrayOf(input), outputMap)

        // Convert results to RectF and send to overlay
        val detectedBoxes = mutableListOf<RectF>()
        val num = numDetections[0].toInt()
        for (i in 0 until num) {
            val box = locations[0][i]
            val left = box[1] * overlay.width
            val top = box[0] * overlay.height
            val right = box[3] * overlay.width
            val bottom = box[2] * overlay.height
            detectedBoxes.add(RectF(left, top, right, bottom))
        }

    }

    fun Bitmap.resizeToModel(): Bitmap {
        return Bitmap.createScaledBitmap(this, 300, 300, true)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // processImageProxy(imageProxy) — do the heavy work on cameraExecutor
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                // handle errors gracefully and log
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun connectToUsbDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No USB device found", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device) ?: run {
            // Request permission if needed
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent("USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(driver.device, permissionIntent)
            Toast.makeText(this, "Requesting USB permission", Toast.LENGTH_SHORT).show()
            return
        }

        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Toast.makeText(this, "Connected to ESP32-S3", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Error opening USB: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        try {
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Toast.makeText(this, "Connected to ESP32-S3", Toast.LENGTH_SHORT).show()

            // Start background thread to read incoming data
            startSerialReading()
        } catch (e: IOException) {
            Toast.makeText(this, "Error opening USB: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    private fun startSerialReading() {
        readThread = Thread {
            val buffer = ByteArray(1024)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val len = usbSerialPort?.read(buffer, 1000) ?: 0
                    if (len > 0) {
                        val received = String(buffer, 0, len)
                        runOnUiThread {
                            // Append received data to the text box
                            val currentText = serialOutputText.text.toString()
                            serialOutputText.text = "$currentText\n$received"
                            // Auto-scroll to bottom
                            val scrollView = serialOutputText.parent as ScrollView
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }
                } catch (e: IOException) {
                    // Connection closed or error → stop thread
                    break
                }
            }
        }
        readThread?.start()
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
                it.write(command.toByteArray(), 1000)  // Timeout 1s
            } catch (e: IOException) {
                Toast.makeText(this, "Error sending: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: Toast.makeText(this, "No USB connection", Toast.LENGTH_SHORT).show()
    }
}