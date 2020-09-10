package com.example.barcodescanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.gson.Gson
import java.io.*
import java.util.*
import java.util.concurrent.Executors

// This is an arbitrary number we are using to keep track of the permission
// request. Where an app has multiple context for requesting permission,
// this can help differentiate the different contexts.
private const val REQUEST_CODE_PERMISSIONS = 10

// This is an array of all the permission specified in the manifest.
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

class MainActivity : AppCompatActivity() {

    class MyBarcode(var date: Calendar, var format: Int, var value_type: Int, var raw_value: String?, var display_type: String?)

    lateinit var feedbacktv:TextView
    lateinit var save_barcode:Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        feedbacktv=findViewById<TextView>(R.id.feedback)

        save_barcode=findViewById(R.id.save)


        viewFinder = findViewById(R.id.view_finder)

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }



    }



    //Barcode ML
    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        lateinit var last_barcodes:ArrayList<FirebaseVisionBarcode>
        lateinit var date: Calendar
        private fun degreesToFirebaseRotation(degrees: Int): Int = when(degrees) {
            0 -> FirebaseVisionImageMetadata.ROTATION_0
            90 -> FirebaseVisionImageMetadata.ROTATION_90
            180 -> FirebaseVisionImageMetadata.ROTATION_180
            270 -> FirebaseVisionImageMetadata.ROTATION_270
            else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
        }

        override fun analyze(imageProxy: ImageProxy?, degrees: Int) {
            val mediaImage = imageProxy?.image
            val imageRotation = degreesToFirebaseRotation(degrees)


            if (mediaImage != null) {
                val image = FirebaseVisionImage.fromMediaImage(mediaImage, imageRotation)
                // Pass image to an ML Kit Vision API
                // ...
                val options = FirebaseVisionBarcodeDetectorOptions.Builder()
                        .setBarcodeFormats(
                                FirebaseVisionBarcode.FORMAT_ALL_FORMATS)
                        .build()

                val detector = FirebaseVision.getInstance()
                        .getVisionBarcodeDetector(options) //lehet az options nem kell bele
                    // Or, to specify the formats to recognize:
                    // val detector = FirebaseVision.getInstance()
                    //        .getVisionBarcodeDetector(options)
                val result = detector.detectInImage(image)
                        .addOnSuccessListener { barcodes ->
                            // Task completed successfully
                            // ...

                            var to_textview=""

                            for (barcode in barcodes) {

                                to_textview+=barcode.displayValue+"\n"
                            }
                            feedbacktv.setText(to_textview)
                            last_barcodes= barcodes as ArrayList<FirebaseVisionBarcode>
                            date=Calendar.getInstance()


                            save_barcode.setOnClickListener {
                                if ( last_barcodes.isEmpty()) Toast.makeText(baseContext, "No barcode!", Toast.LENGTH_SHORT).show()
                                else {
                                    val list = arrayListOf<MyBarcode>()
                                    for (barcode in last_barcodes) {
                                        val obj = MyBarcode(date,barcode.format,barcode.valueType,barcode.rawValue,barcode.displayValue)
                                        list.add(obj)
                                    }
                                    val gson = Gson()
                                    val filename = "Barcode"
                                    val fileContents = gson.toJson(list)

                                    applicationContext.openFileOutput(filename, Context.MODE_APPEND ).use {
                                        it.write(fileContents.toByteArray())
                                    }
                                    applicationContext.openFileOutput(filename, Context.MODE_APPEND ).close()

                                    Toast.makeText(baseContext, "Successful save", Toast.LENGTH_SHORT).show()
                                    var content=applicationContext.openFileInput(filename).bufferedReader().useLines { lines ->
                                        lines.fold("") { some, text ->
                                            "$some\n$text"
                                        }
                                    }
                                    applicationContext.openFileInput(filename).close()
                                    Log.i("Barcode_file:",content)

                                }
                            }


                        }
                        .addOnFailureListener {
                            // Task failed with an exception
                            // ...
                            Log.i("good?","nem sikerült!!!!!!")
                        }
            }
        }

    }



    // CameraX

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var viewFinder: TextureView

    private fun startCamera() {
        //first part--------------------------------------------------------------------------------

        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetResolution(Size(640, 480))
        }.build()


        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }
        //second part--------------------------------------------------------------------------------
        // Create configuration object for the image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
                .apply {
                    // We don't set a resolution for image capture; instead, we
                    // select a capture mode which will infer the appropriate
                    // resolution based on aspect ration and requested mode
                    setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
                }.build()

        // Build the image capture use case and attach button click listener
        val imageCapture = ImageCapture(imageCaptureConfig)
        findViewById<ImageButton>(R.id.capture_button).setOnClickListener {
            val file = File(externalMediaDirs.first(),
                    "${System.currentTimeMillis()}.jpg")

            imageCapture.takePicture(file, executor,
                    object : ImageCapture.OnImageSavedListener {
                        override fun onError(
                                imageCaptureError: ImageCapture.ImageCaptureError,
                                message: String,
                                exc: Throwable?
                        ) {
                            val msg = "Photo capture failed: $message"
                            Log.e("CameraXApp", msg, exc)
                            viewFinder.post {
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onImageSaved(file: File) {
                            val msg = "Photo capture succeeded: ${file.absolutePath}"
                            Log.d("CameraXApp", msg)
                            viewFinder.post {
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
        }
        //third part analyze ------------------------------------------

        // Setup image analysis pipeline
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(
                    ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
        }.build()

        // Build the image analysis use case and instantiate our analyzer
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            setAnalyzer(executor, BarcodeAnalyzer())

        }

        // Bind use cases to lifecycle
        CameraX.bindToLifecycle(this, preview,imageCapture,analyzerUseCase)

    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

}
