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
               // startCameraIfReady()
            } else {
                // Permission denied — show rationale or direct to settings
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                   // showPermissionRationaleDialog()
                } else {
                    // User permanently denied (Don't ask again) — guide them to settings
                   // showGoToSettingsDialog()
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

        overlay = findViewById(R.id.overlay)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Load saved confidence, default to 0.35
        confidenceThreshold = sharedPrefs.getFloat(KEY_CONFIDENCE, 0.35f)
        // Load saved zoom, default to 0f (slider 0%)
        val savedZoom = sharedPrefs.getFloat(KEY_ZOOM, 0f)
        // --- add slider here ---
        val confidenceSlider = findViewById<SeekBar>(R.id.confidenceSlider)
        val confidenceText = findViewById<TextView>(R.id.confidenceText)

        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        panSeekBar = findViewById(R.id.pan_seekbar)
        tiltSeekBar = findViewById(R.id.tilt_seekbar)
        panValueText = findViewById(R.id.pan_value)
        tiltValueText = findViewById(R.id.tilt_value)

        panSeekBar.max = 1000
        tiltSeekBar.max = 1000
        panSeekBar.progress = 500
        tiltSeekBar.progress = 500
        serialOutputText = findViewById(R.id.serial_output)

        val tiltHomingButton: Button = findViewById(R.id.tilt_homing_Button)
        val panHomingButton: Button = findViewById(R.id.pan_homing_Button)
        val bothHomingButton: Button = findViewById(R.id.pan_tilt_homing_Button)

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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnectUsb()
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