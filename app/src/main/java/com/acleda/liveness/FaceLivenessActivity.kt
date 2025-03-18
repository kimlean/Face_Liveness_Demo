package com.acleda.liveness

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.acleda.facelivenesssdk.FaceLivenessSDK
import com.acleda.facelivenesssdk.models.FaceLivenessModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

class FaceLivenessActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "FaceLivenessActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUIRED_CAPTURES = 6
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // Time intervals for captures (ms)
        private val MIN_CAPTURE_INTERVAL = 700L
        private val MAX_CAPTURE_INTERVAL = 1500L
    }

    private lateinit var faceLivenessSDK: FaceLivenessSDK
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageCapture: ImageCapture

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var instructionText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var previewImageView: ImageView
    private lateinit var flashView: View

    // Capture process tracking
    private var captureCount = 0
    private val capturedResults = mutableListOf<FaceLivenessModel>()
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_liveness)

        // Initialize views
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        statusTextView = findViewById(R.id.statusTextView)
        instructionText = findViewById(R.id.instructionText)
        progressBar = findViewById(R.id.progressBar)
        previewImageView = findViewById(R.id.previewImageView)
        flashView = findViewById(R.id.flashView)

        // Initially hide the preview image and flash view
        previewImageView.visibility = View.GONE
        flashView.visibility = View.INVISIBLE
        flashView.alpha = 0f

        // Initialize SDK
        faceLivenessSDK = FaceLivenessSDK.create(applicationContext)

        // Set up camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up capture button
        captureButton.setOnClickListener {
            if (!isCapturing) {
                startCaptureSequence()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Set up preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Set up image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Select front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind any bound use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                instructionText.text = "Position your face within the outline and press Start"

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCaptureSequence() {
        // Reset capture state
        captureCount = 0
        capturedResults.clear()
        isCapturing = true

        // Update UI to show capturing in progress
        captureButton.isEnabled = false
        instructionText.text = "Please hold still and look at the camera"
        statusTextView.text = "Verification in progress..."
        progressBar.progress = 0
        progressBar.max = REQUIRED_CAPTURES
        progressBar.visibility = View.VISIBLE

        // Start the sequence with random intervals to appear natural
        lifecycleScope.launch {
            for (i in 1..REQUIRED_CAPTURES) {
                if (i > 1) {
                    // Random delay between captures
                    val randomDelay = Random.nextLong(MIN_CAPTURE_INTERVAL, MAX_CAPTURE_INTERVAL)
                    delay(randomDelay)
                }

                captureAndAnalyze()

                // Update progress after a slight delay to make it smoother
                delay(100)
                progressBar.progress = i
            }
        }
    }

    private fun captureAndAnalyze() {
        // Capture an image
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // Show a subtle flash animation for feedback without being obvious
                    subtleFlashAnimation()

                    lifecycleScope.launch {
                        // Convert ImageProxy to Bitmap
                        val bitmap = imageToBitmap(image)

                        // Don't show the preview image to make it less obvious
                        // that captures are happening

                        try {
                            // Analyze with Face Liveness SDK
                            val result = faceLivenessSDK.detectLiveness(bitmap)
                            capturedResults.add(result)
                            captureCount++

                            // Update instruction text to show progress
                            withContext(Dispatchers.Main) {
                                instructionText.text = "Verification in progress: ${captureCount}/${REQUIRED_CAPTURES}"
                            }

                            // If we have all required captures, show results
                            if (captureCount >= REQUIRED_CAPTURES) {
                                showAggregatedResults()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Analysis failed", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@FaceLivenessActivity,
                                    "Verification failed: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                captureButton.isEnabled = true
                                instructionText.text = "Please try again"
                                isCapturing = false
                            }
                        }

                        // Always close the image
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    Toast.makeText(
                        this@FaceLivenessActivity,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    captureButton.isEnabled = true
                    instructionText.text = "Please try again"
                    isCapturing = false
                }
            }
        )
    }

    private fun subtleFlashAnimation() {
        // Make the flash view visible
        flashView.visibility = View.VISIBLE

        // Animate the alpha value from 0 to 0.3 and back to 0
        val animator = ValueAnimator.ofFloat(0f, 0.3f, 0f)
        animator.duration = 300 // 300ms for the whole animation
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val alpha = animation.animatedValue as Float
            flashView.alpha = alpha
        }
        animator.start()
    }

    private suspend fun imageToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        return withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun showAggregatedResults() {
        // Count live vs spoof results
        val liveCount = capturedResults.count { it.prediction == "Live" }
        val spoofCount = REQUIRED_CAPTURES - liveCount

        // Calculate average confidence
        val avgConfidence = capturedResults
            .map { if (it.prediction == "Live") it.confidence else 1 - it.confidence }
            .average()

        // Calculate overall quality
        val avgQuality = capturedResults
            .map { it.qualityResult.overallScore }
            .average()

        // Make final determination
        // If more than half are live AND average confidence is sufficient, consider it real
        val isLive = if (liveCount >= spoofCount) {
            // If many live results, weight more towards live
            true
        } else {
            // If many spoof results, but confidence is very low, might still be live
            val avgSpoofConfidence = capturedResults
                .filter { it.prediction != "Live" }
                .map { it.confidence }
                .average()

            // If spoof confidence is below 0.6, consider it might be a false positive
            avgSpoofConfidence < 0.6 && liveCount >= 2
        }

        // Calculate percentages
        val livePercentage = (liveCount * 100.0 / REQUIRED_CAPTURES).roundToInt()
        val confidencePercentage = (avgConfidence * 100).roundToInt()
        val qualityPercentage = (avgQuality * 100).roundToInt()

        // Navigate to results screen or update UI
        showResultsScreen(isLive, livePercentage, confidencePercentage, qualityPercentage)
    }

    private fun showResultsScreen(
        isLive: Boolean,
        livePercentage: Int,
        confidencePercentage: Int,
        qualityPercentage: Int
    ) {
        runOnUiThread {
            // Reset capturing flag
            isCapturing = false

            // Update UI elements
            previewView.visibility = View.GONE
            previewImageView.visibility = View.VISIBLE
            progressBar.visibility = View.GONE

            // Set the status text background color based on result
            statusTextView.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (isLive) R.color.status_success else R.color.status_failure
                )
            )

            val resultText = StringBuilder()
            if (isLive) {
                resultText.append("✅ VERIFICATION SUCCESSFUL\n\n")
                resultText.append("Your identity has been verified as a real person.\n\n")
            } else {
                resultText.append("❌ VERIFICATION FAILED\n\n")
                resultText.append("We could not verify you as a real person.\n\n")
            }

            resultText.append("VERIFICATION DETAILS:\n")
            resultText.append("• Live Detection: $livePercentage%\n")
            resultText.append("• Confidence: $confidencePercentage%\n")
            resultText.append("• Image Quality: $qualityPercentage%\n\n")

            // Add technical details
            resultText.append("TECHNICAL INFORMATION:\n")
            capturedResults.forEachIndexed { index, result ->
                resultText.append("• Frame ${index + 1}: ${result.prediction} (${(result.confidence * 100).roundToInt()}%)\n")
            }

            statusTextView.text = resultText.toString()

            // Update button
            captureButton.text = "Try Again"
            captureButton.isEnabled = true
            instructionText.text = if (isLive) "Verification successful!" else "Verification failed. Please try again."

            // Update button click listener
            captureButton.setOnClickListener {
                resetUI()
            }
        }
    }

    private fun resetUI() {
        // Reset state
        captureCount = 0
        capturedResults.clear()
        isCapturing = false

        // Reset UI
        previewView.visibility = View.VISIBLE
        previewImageView.visibility = View.GONE
        captureButton.text = "Start Verification"
        instructionText.text = "Position your face within the outline and press Start"
        statusTextView.text = ""
        statusTextView.setBackgroundColor(ContextCompat.getColor(this, R.color.status_neutral))
        progressBar.visibility = View.INVISIBLE

        // Reset button listener
        captureButton.setOnClickListener {
            startCaptureSequence()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required for this feature",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceLivenessSDK.close()
    }
}