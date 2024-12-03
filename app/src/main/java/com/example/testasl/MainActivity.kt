package com.example.testasl

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var webSocketClient: WebSocketClient? = null

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

        createWebSocketClient()

        // Request camera permission
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()


    }

    private fun createWebSocketClient() {
        Log.e(TAG, "====== WebSocket Connection Process Starting ======")

        val serverUrl = "wss://asl.yazidrizkik.dev"
        Log.e(TAG, "Attempting to connect to server at: $serverUrl")

        try {
            // Log before WebSocket client creation
            Log.e(TAG, "Creating WebSocket client instance...")
            val headers = HashMap<String, String>()
            headers["User-Agent"] = "AndroidWebSocket/1.0"
            headers["Upgrade"] = "websocket"
            headers["Connection"] = "Upgrade"

            webSocketClient = object : WebSocketClient(URI(serverUrl)) {
                init {
                    // Log when the WebSocket client is instantiated
                    Log.e(TAG, "WebSocket client instantiated")
                }

                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.e(TAG, "WebSocket onOpen called")
                    Log.e(TAG, "Handshake status: ${handshakedata?.httpStatus}")
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            // Log the raw response first (truncated to avoid flooding logs)
                            val truncatedMessage = if (it.length > 100) "${it.take(100)}..." else it
                            Log.d(TAG, "Raw server response: $truncatedMessage")

                            // Parse the JSON response
                            val response = JSONObject(it)

                            // Log the structure of the response
                            Log.d(TAG, "Response contains: ${response.keys().asSequence().toList()}")

                            // Check for hand detection
                            val handDetected = response.getBoolean("hand_detected")
                            Log.d(TAG, "Hand detected: $handDetected")

                            if (handDetected) {
                                // Get predictions array
                                val predictions = response.getJSONArray("predictions")
                                Log.d(TAG, "Number of predictions: ${predictions.length()}")

                                // Process each prediction
                                for (i in 0 until predictions.length()) {
                                    val prediction = predictions.getJSONObject(i)
                                    val sign = prediction.getString("sign")
                                    val confidence = prediction.getDouble("confidence")
                                    Log.d(TAG, "Prediction $i: $sign (${confidence * 100}%)")
                                }

                                // Get landmarks if available
                                response.optJSONArray("landmarks")?.let { landmarks ->
                                    Log.d(TAG, "Received ${landmarks.length()} landmarks")
                                }

                                // Update UI with the top prediction
                                val topPrediction = predictions.getJSONObject(0)
                                runOnUiThread {
                                    // Format the confidence as a percentage with one decimal place
                                    val confidence = topPrediction.getDouble("confidence") * 100
                                    val sign = topPrediction.getString("sign")

                                    // Create a more detailed prediction text
                                    val predictionText = buildString {
                                        append("Sign: $sign (${String.format("%.1f", confidence)}%)")

                                        // Add top 3 predictions if available
                                        if (predictions.length() > 1) {
                                            append("\nAlternatives:")
                                            for (i in 1 until minOf(3, predictions.length())) {
                                                val altPrediction = predictions.getJSONObject(i)
                                                val altSign = altPrediction.getString("sign")
                                                val altConf = altPrediction.getDouble("confidence") * 100
                                                append("\n${altSign}: ${String.format("%.1f", altConf)}%")
                                            }
                                        }
                                    }

                                    binding.predictionText.text = predictionText
                                }
                            } else {
                                Log.d(TAG, "No hand detected in this frame")
                                runOnUiThread {
                                    binding.predictionText.text = "No hand detected"
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing server response", e)
                            Log.e(TAG, "Failed message content: $message")
                            e.printStackTrace()

                            runOnUiThread {
                                Toast.makeText(
                                    applicationContext,
                                    "Error processing response: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.e(TAG, "WebSocket onClose called - Code: $code, Reason: $reason")
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket onError called")
                    Log.e(TAG, "Error details: ${ex?.message}")
                    ex?.printStackTrace()
                }
            }

            // Log after client creation but before connect
            Log.e(TAG, "WebSocket client created, preparing to connect...")

            // Add connection state check
            Log.e(TAG, "Current connection state: ${webSocketClient?.connection?.isOpen}")

            // Wrap the connect call in try-catch
            try {
                Log.e(TAG, "Calling connect()...")
                webSocketClient?.connect()
                Log.e(TAG, "Connect call completed")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connect() call", e)
            }

            // Log after connection attempt
            Log.e(TAG, "Connection attempt completed")

        } catch (e: Exception) {
            // Log any exceptions during the entire process
            Log.e(TAG, "Exception in createWebSocketClient", e)
            e.printStackTrace()
        }

        Log.e(TAG, "====== WebSocket Connection Process Completed ======")
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

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
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            Log.d(TAG, "Starting image processing")

            // Convert ImageProxy to Bitmap
            val bitmap = imageProxy.toBitmap()
            Log.d(TAG, "Converted ImageProxy to Bitmap: ${bitmap.width}x${bitmap.height}")

            // Rotate the bitmap based on camera orientation
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())
            Log.d(TAG, "Rotated bitmap: ${rotatedBitmap.width}x${rotatedBitmap.height}")

            // Compress and convert to base64
            val outputStream = ByteArrayOutputStream()
            // Reduce JPEG quality to 50 to improve transmission speed
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
            Log.d(TAG, "Compressed and encoded image, size: ${base64Image.length} bytes")

            // Send to server if connected
            webSocketClient?.let { client ->
                if (client.isOpen) {
                    Log.d(TAG, "Sending frame to server...")
                    client.send(base64Image)
                } else {
                    Log.e(TAG, "WebSocket is not open, cannot send frame")
                }
            } ?: Log.e(TAG, "WebSocket client is null")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        webSocketClient?.close()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}