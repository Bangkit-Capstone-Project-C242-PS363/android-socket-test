package com.example.testasl

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.testasl.databinding.ActivityMainBinding
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    // Essential UI and camera components
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var webSocketClient: WebSocketClient? = null

    // Simple frame processing control
    private var isProcessingFrame = false

    private var lastFrameSentTime = 0L
    private val FRAME_INTERVAL = 100L  // 100ms between frames (10 FPS)
    private var totalFramesReceived = 0
    private var totalFramesSent = 0
    // Performance monitoring variables
    private var frameCount = 0
    private var lastFpsUpdateTime = System.currentTimeMillis()
    private var currentFps = 0f

    // Camera permission handling using the modern activity result API
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize critical components
        setupWebSocket()
        initializeCamera()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initializeCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupWebSocket() {
//        val serverUrl = "wss://inference-model2-1032595886811.asia-southeast2.run.app/"
        val serverUrl = "wss://inference-model-kji5w4ybbq-et.a.run.app/"
        Log.d(TAG, "Initializing WebSocket connection to: $serverUrl")

        webSocketClient = object : WebSocketClient(URI(serverUrl)) {
            override fun onOpen(handshake: ServerHandshake?) {
                Log.d(TAG, "WebSocket connection established")
                runOnUiThread {
                    Toast.makeText(applicationContext, "Server Connected", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMessage(message: String?) {
                message?.let { handleServerResponse(it) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket connection closed: $reason")
                runOnUiThread {
                    Toast.makeText(applicationContext, "Server Disconnected", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error occurred: ${ex?.message}")
            }
        }.apply { connect() }
    }

//    private fun handleServerResponse(message: String) {
//        try {
//            val response = JSONObject(message)
//            val handDetected = response.getBoolean("hand_detected")
//
//            if (handDetected) {
//                val predictions = response.getJSONArray("predictions")
//                val landmarks = response.optJSONArray("landmarks")
//                updatePredictionDisplay(predictions, landmarks)
//            } else {
//                runOnUiThread {
//                    binding.predictionText.text = "No hand detected"
//                }
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Error processing server response: ${e.message}")
//        }
//    }

    private fun updatePredictionDisplay(predictions: JSONArray, landmarks: JSONArray?) {
        runOnUiThread {
            val predictionText = buildString {
                // Display predictions in order of confidence
                for (i in 0 until predictions.length()) {
                    val prediction = predictions.getJSONObject(i)
                    val sign = prediction.getString("sign")
                    val confidence = prediction.getDouble("confidence")
                    append("#${i + 1}: $sign (${String.format("%.2f", confidence * 100)}%)\n")
                }

                // Add landmark count if available
                landmarks?.let {
                    append("\nDetected ${it.length()} landmarks")
                }
            }
            binding.predictionText.text = predictionText
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Configure camera preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // Configure image analysis
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use cases: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        totalFramesReceived++  // Count every frame that comes from the camera

        // Check if enough time has passed since the last frame was sent
        if (isProcessingFrame || currentTime - lastFrameSentTime < FRAME_INTERVAL) {
            imageProxy.close()
            return
        }

        try {
            isProcessingFrame = true
            lastFrameSentTime = currentTime  // Update the last sent time

            // Convert ImageProxy to Bitmap
            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())

            // Compress to JPEG format
            val outputStream = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)

            // Encode as base64 string
            val base64Image = Base64.encodeToString(
                outputStream.toByteArray(),
                Base64.NO_WRAP
            )

            // Send to server if connection is available
            webSocketClient?.takeIf { it.isOpen }?.let { client ->
                client.send(base64Image)
                totalFramesSent++  // Count successfully sent frames
                updateFps()        // Update FPS display
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}")
        } finally {
            isProcessingFrame = false
            imageProxy.close()
        }
    }

    private fun updateFps() {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastFpsUpdateTime

        // Update FPS display every second
        if (elapsedTime >= 1000) {
            val receivedFps = (totalFramesReceived * 1000f) / elapsedTime
            val sentFps = (totalFramesSent * 1000f) / elapsedTime

            runOnUiThread {
                binding.fpsText.text = buildString {
                    append("Camera: ${String.format("%.1f", receivedFps)} FPS\n")
                    append("Sent: ${String.format("%.1f", sentFps)} FPS")
                }
            }

            // Reset counters
            totalFramesReceived = 0
            totalFramesSent = 0
            lastFpsUpdateTime = currentTime
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

//    private fun updateFps() {
//        frameCount++
//        val currentTime = System.currentTimeMillis()
//        val elapsedTime = currentTime - lastFpsUpdateTime
//
//        if (elapsedTime >= 1000) {
//            currentFps = (frameCount * 1000f) / elapsedTime
//            frameCount = 0
//            lastFpsUpdateTime = currentTime
//
//            runOnUiThread {
//                binding.fpsText.text = "FPS: ${String.format("%.1f", currentFps)}"
//            }
//        }
//    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        webSocketClient?.close()
    }

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val circlePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 3f
    }

    // Define the hand connections (similar to MediaPipe's HAND_CONNECTIONS)
    private val handConnections = listOf(
        // Thumb
        Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
        // Index finger
        Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
        // Middle finger
        Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
        // Ring finger
        Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
        // Pinky
        Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
        // Palm
        Pair(5, 9), Pair(9, 13), Pair(13, 17)
    )

    private fun handleServerResponse(message: String) {
        try {
            val response = JSONObject(message)
            val handDetected = response.getBoolean("hand_detected")

            if (handDetected) {
                val predictions = response.getJSONArray("predictions")
                val landmarks = response.optJSONArray("landmarks")

                // Create a bitmap for drawing
                val drawingBitmap = Bitmap.createBitmap(
                    binding.viewFinder.width,
                    binding.viewFinder.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(drawingBitmap)

                // Draw landmarks if available
                landmarks?.let {
                    drawHandLandmarks(canvas, it)
                }

                // Update UI with predictions and drawing
                runOnUiThread {
                    // Update prediction text
                    updatePredictionDisplay(predictions,landmarks)

                    // Display the drawing overlay
                    binding.overlayView.setImageBitmap(drawingBitmap)
                }
            } else {
                runOnUiThread {
                    binding.predictionText.text = "No hand detected"
                    binding.overlayView.setImageBitmap(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing response", e)
        }
    }

    private fun drawHandLandmarks(canvas: Canvas, landmarks: JSONArray) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()

        // Store landmark positions for drawing connections
        val points = mutableListOf<PointF>()

        // Draw each landmark point
        for (i in 0 until landmarks.length()) {
            val landmark = landmarks.getJSONObject(i)
            val x = landmark.getDouble("x").toFloat() * width
            val y = landmark.getDouble("y").toFloat() * height
            points.add(PointF(x, y))

            // Draw landmark point
            canvas.drawCircle(x, y, 8f, circlePaint)
        }

        // Draw connections between landmarks
        for ((start, end) in handConnections) {
            if (points.size > start && points.size > end) {
                val startPoint = points[start]
                val endPoint = points[end]
                canvas.drawLine(
                    startPoint.x, startPoint.y,
                    endPoint.x, endPoint.y,
                    paint
                )
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}