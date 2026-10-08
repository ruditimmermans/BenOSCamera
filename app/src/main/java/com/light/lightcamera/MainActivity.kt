package com.light.lightcamera

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.light.lightcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class MainActivity : AppCompatActivity() {
    // True for the QR-to-clipboard entry point (QrToClipboardActivity). In that mode the first
    // code decoded is copied to the clipboard and the activity finishes, which returns to the
    // task that launched it. Capture, recording, gallery and settings are unavailable.
    protected open val qrToClipboardMode: Boolean = false
    private var qrCopied = false

    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var qrScannerEnabled = false
    private var tapToTakePhoto = false
    private var autoZoom = true
    private var storageLocation = "internal"
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var screenAspectRatio = AspectRatio.RATIO_4_3
    private var lastPhotoTime = 0L
    private var isZoomSeekBarTouching = false

    private var orientationEventListener: OrientationEventListener? = null
    private var currentRotationDegrees = 0

    private val screenRecordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenRecordService.startService(this, result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (isCaptureIntent()) {
            setResult(RESULT_CANCELED)
        }

        setupSecureWindowFlags()
        adjustUiForIntent()

        loadSettings()
        setupOrientationListener()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }
        viewBinding.screenRecordButton.setOnClickListener { onScreenRecordClicked() }
        viewBinding.qrButton.setOnClickListener { toggleQrScanner() }
        viewBinding.switchCameraButton.setOnClickListener { switchCamera() }
        viewBinding.flashButton.setOnClickListener { toggleFlashMode() }
        viewBinding.galleryButton.setOnClickListener { openGallery() }
        viewBinding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        val options = BarcodeScannerOptions.Builder()
            .setZoomSuggestionOptions(ZoomSuggestionOptions.Builder { zoomRatio ->
                if (autoZoom) {
                    camera?.cameraControl?.setZoomRatio(zoomRatio)
                    true
                } else {
                    false
                }
            }.build())
            .enableAllPotentialBarcodes()
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        updateQrIcon()
        updateFlashIcon()
        setupZoom()

        if (qrToClipboardMode) {
            setupQrToClipboardMode()
        } else {
            checkForUpdates()
        }
    }

    private fun setupQrToClipboardMode() {
        qrScannerEnabled = true
        listOf(
            viewBinding.imageCaptureButton,
            viewBinding.videoCaptureButton,
            viewBinding.screenRecordButton,
            viewBinding.galleryButton,
            viewBinding.settingsButton,
            viewBinding.qrButton
        ).forEach { it.visibility = View.GONE }
    }

    // Copies the decoded text, reports it, and closes the scanner. Guarded so that frames already
    // in flight when the first code is decoded cannot copy a second value.
    private fun copyToClipboardAndFinish(value: String) {
        if (qrCopied) return
        qrCopied = true
        qrScannerEnabled = false
        runOnUiThread {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText(getString(R.string.qr_clip_label), value)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.qr_copied_to_clipboard, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun isPhotoCaptureIntent(): Boolean {
        val action = intent?.action
        return action == MediaStore.ACTION_IMAGE_CAPTURE ||
                action == "android.media.action.IMAGE_CAPTURE_SECURE"
    }

    private fun isVideoCaptureIntent(): Boolean {
        return intent?.action == MediaStore.ACTION_VIDEO_CAPTURE
    }

    private fun isCaptureIntent(): Boolean {
        return isPhotoCaptureIntent() || isVideoCaptureIntent()
    }

    private fun isSecureCameraMode(): Boolean {
        val action = intent?.action
        val isSecureIntent = action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE ||
                action == "android.media.action.IMAGE_CAPTURE_SECURE"
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        return isSecureIntent || (keyguardManager?.isKeyguardLocked == true)
    }

    private fun setupSecureWindowFlags() {
        if (isSecureCameraMode()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            viewBinding.galleryButton.visibility = View.GONE
        }
    }

    private fun adjustUiForIntent() {
        if (isPhotoCaptureIntent()) {
            viewBinding.videoCaptureButton.visibility = View.GONE
        } else if (isVideoCaptureIntent()) {
            viewBinding.imageCaptureButton.visibility = View.GONE
        }
    }

    private fun getExtraOutputUri(): Uri? {
        val intent = intent ?: return null
        return IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
    }

    private fun checkForUpdates() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!sharedPrefs.getBoolean("auto_check_updates", true)) return

        val updateManager = UpdateManager(this)
        lifecycleScope.launch {
            val result = updateManager.checkForUpdates()
            if (result is UpdateManager.UpdateResult.NewVersionAvailable) {
                val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.check_for_updates)
                    .setMessage(getString(R.string.update_available, result.version))
                    .setPositiveButton(R.string.download_update) { _, _ ->
                        updateManager.downloadAndInstallUpdate(result.downloadUrl, result.version)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()

                dialog.window?.let { window ->
                    window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    val layoutParams = window.attributes
                    layoutParams.y = (32 * resources.displayMetrics.density).toInt()
                    window.attributes = layoutParams
                }

                dialog.show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val oldFlash = flashMode
        val oldRatio = screenAspectRatio
        loadSettings()
        applyButtonColor()

        if (oldFlash != flashMode || oldRatio != screenAspectRatio) {
            startCamera()
        }
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener?.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener?.disable()
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                val rotationDegrees = when (rotation) {
                    Surface.ROTATION_0 -> 0
                    Surface.ROTATION_90 -> 270
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 90
                    else -> 0
                }

                if (rotationDegrees != currentRotationDegrees) {
                    currentRotationDegrees = rotationDegrees
                    updateUiRotation(rotationDegrees)

                    // Update camera target rotation
                    imageCapture?.targetRotation = rotation
                    videoCapture?.targetRotation = rotation
                }
            }
        }
    }

    private fun updateUiRotation(degrees: Int) {
        val rotation = (-degrees).toFloat()

        val viewsToRotate = listOf(
            viewBinding.qrButton,
            viewBinding.switchCameraButton,
            viewBinding.flashButton,
            viewBinding.galleryButton,
            viewBinding.videoCaptureButton,
            viewBinding.screenRecordButton,
            viewBinding.settingsButton
        )

        viewsToRotate.forEach { view ->
            view.animate()
                .rotation(rotation)
                .setDuration(300)
                .start()
        }

        // Also rotate the FAB icon specifically
        viewBinding.imageCaptureButton.animate()
            .rotation(rotation)
            .setDuration(300)
            .start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    R.string.permission_denied,
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadSettings() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        val flashValue = sharedPrefs.getString(KEY_FLASH_MODE, "0")?.toInt() ?: 0
        flashMode = when (flashValue) {
            1 -> ImageCapture.FLASH_MODE_ON
            2 -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        val ratioValue = sharedPrefs.getString(KEY_ASPECT_RATIO, "0")?.toInt() ?: 0
        screenAspectRatio = if (ratioValue == 1) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

        tapToTakePhoto = sharedPrefs.getBoolean(KEY_TAP_TO_TAKE_PHOTO, false)
        autoZoom = sharedPrefs.getBoolean(KEY_AUTO_ZOOM, true)
        storageLocation = sharedPrefs.getString(KEY_STORAGE_LOCATION, "internal") ?: "internal"

        lensFacing = sharedPrefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
    }

    private fun applyButtonColor() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val color = sharedPrefs.getInt(KEY_BUTTON_COLOR, Color.WHITE)
        val colorStateList = ColorStateList.valueOf(color)

        viewBinding.settingsButton.imageTintList = colorStateList
        viewBinding.switchCameraButton.imageTintList = colorStateList
        viewBinding.flashButton.imageTintList = colorStateList
        viewBinding.galleryButton.imageTintList = colorStateList
        viewBinding.screenRecordButton.imageTintList = colorStateList

        if (recording == null) {
            viewBinding.videoCaptureButton.imageTintList = colorStateList
        }

        viewBinding.imageCaptureButton.backgroundTintList = colorStateList
        val contrastColor = if (isColorLight(color)) Color.BLACK else Color.WHITE
        viewBinding.imageCaptureButton.imageTintList = ColorStateList.valueOf(contrastColor)

        viewBinding.zoomSeekBar.progressTintList = colorStateList
        viewBinding.zoomSeekBar.thumbTintList = colorStateList

        updateQrIcon()
    }

    private fun isColorLight(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness < 0.5
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount != 0) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhoto()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val newZoom = if (delta > 0) currentZoomRatio * 1.05f else currentZoomRatio / 1.05f
                camera?.cameraControl?.setZoomRatio(newZoom)
                return true
            }
        } else if (event.action == MotionEvent.ACTION_BUTTON_PRESS &&
            (event.buttonState == MotionEvent.BUTTON_PRIMARY ||
                    event.buttonState == MotionEvent.BUTTON_STYLUS_PRIMARY)) {
            if (tapToTakePhoto) {
                takePhoto()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE -> {
                    takePhoto()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupZoom() {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        }
        val scaleGestureDetector = ScaleGestureDetector(this, listener)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (tapToTakePhoto) {
                    takePhoto()
                    return true
                }
                return false
            }
        })

        viewBinding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        viewBinding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    camera?.cameraControl?.setLinearZoom(progress / 1000f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isZoomSeekBarTouching = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isZoomSeekBarTouching = false
                camera?.cameraControl?.setLinearZoom(viewBinding.zoomSeekBar.progress / 1000f)
            }
        })
    }

    private fun toggleQrScanner() {
        qrScannerEnabled = !qrScannerEnabled
        updateQrIcon()
        if (!qrScannerEnabled) {
            camera?.cameraControl?.setZoomRatio(1f)
        }
    }

    private fun updateQrIcon() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val baseColor = sharedPrefs.getInt(KEY_BUTTON_COLOR, Color.WHITE)
        val color = if (qrScannerEnabled) Color.YELLOW else baseColor
        viewBinding.qrButton.imageTintList = ColorStateList.valueOf(color)
    }

    private fun toggleFlashMode() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        val flashValue = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "1"
            ImageCapture.FLASH_MODE_AUTO -> "2"
            else -> "0"
        }

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPrefs.edit().putString(KEY_FLASH_MODE, flashValue).apply()

        imageCapture?.flashMode = flashMode
        updateFlashIcon()

        if (recording != null) {
            camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
        }
    }

    private fun updateFlashIcon() {
        val iconRes = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        viewBinding.flashButton.setImageResource(iconRes)
    }

    private fun switchCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        with(sharedPrefs.edit()) {
            putInt(KEY_LENS_FACING, lensFacing)
            apply()
        }

        startCamera()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.type = "image/*"
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_to_open_photo), Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        if (qrToClipboardMode) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPhotoTime < 1000) return
        lastPhotoTime = currentTime

        val imageCapture = imageCapture ?: return

        // Shutter effect
        viewBinding.shutterEffectView.visibility = View.VISIBLE
        viewBinding.shutterEffectView.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                viewBinding.shutterEffectView.visibility = View.GONE
                viewBinding.shutterEffectView.alpha = 1f
            }
            .start()

        val extraOutputUri = getExtraOutputUri()

        if (isPhotoCaptureIntent()) {
            if (extraOutputUri != null) {
                takePhotoToUri(imageCapture, extraOutputUri)
            } else {
                takePhotoToThumbnail(imageCapture)
            }
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BenOSCamera")
            }
        }

        val imageCollection = if (storageLocation == "sd_card") {
            getSDCardUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, imageCollection, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("MainActivity", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
                    val msg = getString(R.string.photo_captured, time)
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "Photo capture succeeded: ${output.savedUri}")
                }
            }
        )
    }

    private fun takePhotoToUri(imageCapture: ImageCapture, outputUri: Uri) {
        try {
            val outputStream = contentResolver.openOutputStream(outputUri)
            if (outputStream != null) {
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream).build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("MainActivity", "Photo capture failed: ${exc.message}", exc)
                            setResult(RESULT_CANCELED)
                            finish()
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val resultIntent = Intent().setData(outputUri)
                            resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        }
                    }
                )
            } else {
                saveToMediaStoreAndFinish(imageCapture, outputUri)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening output stream for URI: $outputUri", e)
            saveToMediaStoreAndFinish(imageCapture, outputUri)
        }
    }

    private fun saveToMediaStoreAndFinish(imageCapture: ImageCapture, targetUri: Uri) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BenOSCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("MainActivity", "Photo capture failed: ${exc.message}", exc)
                    setResult(RESULT_CANCELED)
                    finish()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: targetUri
                    val resultIntent = Intent().setData(savedUri)
                    resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        )
    }

    private fun takePhotoToThumbnail(imageCapture: ImageCapture) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("MainActivity", "Photo capture failed: ${exc.message}", exc)
                    setResult(RESULT_CANCELED)
                    finish()
                }

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    imageProxy.close()

                    val rotatedBitmap = if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }

                    val maxDim = 512
                    val scaledBitmap = if (rotatedBitmap.width > maxDim || rotatedBitmap.height > maxDim) {
                        val scale = maxDim.toFloat() / Math.max(rotatedBitmap.width, rotatedBitmap.height)
                        android.graphics.Bitmap.createScaledBitmap(
                            rotatedBitmap,
                            (rotatedBitmap.width * scale).toInt(),
                            (rotatedBitmap.height * scale).toInt(),
                            true
                        )
                    } else {
                        rotatedBitmap
                    }

                    val resultIntent = Intent().putExtra("data", scaledBitmap)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val extraOutputUri = getExtraOutputUri()
        var pfd: ParcelFileDescriptor? = null

        val pendingRecording = if (isVideoCaptureIntent() && extraOutputUri != null) {
            try {
                pfd = contentResolver.openFileDescriptor(extraOutputUri, "rw")
                if (pfd != null) {
                    val fileDescriptorOptions = FileDescriptorOutputOptions.Builder(pfd).build()
                    videoCapture.output.prepareRecording(this, fileDescriptorOptions)
                } else {
                    videoCapture.output.prepareRecording(this, createDefaultMediaStoreVideoOptions())
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to open PFD for video output", e)
                videoCapture.output.prepareRecording(this, createDefaultMediaStoreVideoOptions())
            }
        } else {
            videoCapture.output.prepareRecording(this, createDefaultMediaStoreVideoOptions())
        }

        recording = pendingRecording
            .apply {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (flashMode == ImageCapture.FLASH_MODE_ON) {
                            camera?.cameraControl?.enableTorch(true)
                        }
                        viewBinding.videoCaptureButton.apply {
                            setImageResource(R.drawable.ic_stop)
                            imageTintList = ColorStateList.valueOf(Color.RED)
                            contentDescription = getString(R.string.stop_video)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        try {
                            pfd?.close()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error closing PFD", e)
                        }

                        if (!recordEvent.hasError()) {
                            val savedUri = extraOutputUri ?: recordEvent.outputResults.outputUri

                            if (isVideoCaptureIntent()) {
                                val resultIntent = Intent().setData(savedUri)
                                resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                setResult(RESULT_OK, resultIntent)
                                finish()
                            } else {
                                val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                                Log.d("MainActivity", msg)

                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(savedUri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to open video", e)
                                    Toast.makeText(baseContext, getString(R.string.no_app_to_open_video), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            recording?.close()
                            recording = null
                            Log.e("MainActivity", "Video capture ends with error: ${recordEvent.error}")
                            if (isVideoCaptureIntent()) {
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                        }
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        camera?.cameraControl?.enableTorch(false)
                        viewBinding.videoCaptureButton.apply {
                            setImageResource(R.drawable.ic_videocam)
                            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                            val baseColor = sharedPrefs.getInt(KEY_BUTTON_COLOR, Color.WHITE)
                            imageTintList = ColorStateList.valueOf(baseColor)
                            contentDescription = getString(R.string.record_video)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun createDefaultMediaStoreVideoOptions(): MediaStoreOutputOptions {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BenOSCamera")
            }
        }

        val videoCollection = if (storageLocation == "sd_card") {
            getSDCardUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        return MediaStoreOutputOptions
            .Builder(contentResolver, videoCollection)
            .setContentValues(contentValues)
            .build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val initialRotation = when (currentRotationDegrees) {
                90 -> Surface.ROTATION_270
                180 -> Surface.ROTATION_180
                270 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setFlashMode(flashMode)
                .setTargetRotation(initialRotation)
                .build()

            videoCapture?.targetRotation = initialRotation

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer
                )

                camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                    if (!isZoomSeekBarTouching) {
                        viewBinding.zoomSeekBar.progress = (zoomState.linearZoom * 1000).toInt()
                    }
                }
                viewBinding.zoomSeekBar.visibility = View.VISIBLE
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!qrScannerEnabled) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue

                        if (qrToClipboardMode) {
                            copyToClipboardAndFinish(rawValue)
                            break
                        }

                        if (barcode.valueType == Barcode.TYPE_URL) {
                            val url = barcode.url?.url
                            if (url != null) {
                                qrScannerEnabled = false
                                runOnUiThread {
                                    updateQrIcon()
                                    camera?.cameraControl?.setZoomRatio(1f)
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to open URL: $url", e)
                                        Toast.makeText(this, getString(R.string.no_app_to_open_url), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                break
                            }
                        }

                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.scanned_result, rawValue), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("MainActivity", "Barcode scanning failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun getSDCardUri(defaultUri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return defaultUri

        val volumes = MediaStore.getExternalVolumeNames(this)
        val sdCardVolume = volumes.find { it != MediaStore.VOLUME_EXTERNAL_PRIMARY && it != MediaStore.VOLUME_EXTERNAL }

        return if (sdCardVolume != null) {
            if (defaultUri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                MediaStore.Images.Media.getContentUri(sdCardVolume)
            } else {
                MediaStore.Video.Media.getContentUri(sdCardVolume)
            }
        } else {
            defaultUri
        }
    }

    private fun onScreenRecordClicked() {
        checkAndRequestOverlayPermission {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val countdownSeconds = prefs.getString("screen_record_countdown", "0")?.toIntOrNull() ?: 0

            if (countdownSeconds > 0) {
                startCountdownAndRecord(countdownSeconds)
            } else {
                launchScreenCaptureIntent()
            }
        }
    }

    private fun checkAndRequestOverlayPermission(onGranted: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val showOverlay = prefs.getBoolean("screen_record_overlay", true)

        if (showOverlay && !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    onGranted()
                }
                .show()
        } else {
            onGranted()
        }
    }

    private fun startCountdownAndRecord(seconds: Int) {
        var remaining = seconds
        val toast = Toast.makeText(this, "$remaining…", Toast.LENGTH_SHORT)
        toast.show()

        object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remaining--
                if (remaining > 0) {
                    toast.setText("$remaining…")
                    toast.show()
                }
            }

            override fun onFinish() {
                launchScreenCaptureIntent()
            }
        }.start()
    }

    private fun launchScreenCaptureIntent() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenRecordLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    companion object {
        private const val KEY_FLASH_MODE = "flash_mode"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
        private const val KEY_BUTTON_COLOR = "button_color"
        private const val KEY_TAP_TO_TAKE_PHOTO = "tap_to_take_photo"
        private const val KEY_AUTO_ZOOM = "auto_zoom"
        private const val KEY_STORAGE_LOCATION = "storage_location"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
