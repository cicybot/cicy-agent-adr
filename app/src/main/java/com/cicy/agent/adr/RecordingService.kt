package com.cicy.agent.adr

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cicy.agent.R
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

// video const
const val MAX_SCREEN_SIZE = 1200

class RecordingService : Service() {
    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null
    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "cicy:wakelock"
        )
    }

    companion object {
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _screenImgData = "" // screen capture start status

        private var compressQuality: Int = 20

        val screenImgData: String
            get() = _screenImgData
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val CompressQuality: Int
            get() = compressQuality

    }

    private val logTag = "LOG_SERVICE"
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33
    private var isHalfScale: Boolean? = null

    // video
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        Log.d(
            logTag,
            "RecordingService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay"
        )
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()
        createForegroundNotification()
    }


    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
        imageReader =
            ImageReader.newInstance(
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                PixelFormat.RGBA_8888,
                2
            ).apply {

                setOnImageAvailableListener({ imageReader: ImageReader ->
                    var bitmap: Bitmap? = null
                    try {
                        imageReader.acquireLatestImage().use { image ->
                            if (image == null) {
                                return@setOnImageAvailableListener
                            }
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding: Int = rowStride - pixelStride * SCREEN_INFO.width
                            val rightPadding: Int = rowPadding / pixelStride

                            val originalBitmap = Bitmap.createBitmap(
                                SCREEN_INFO.width + rightPadding,
                                SCREEN_INFO.height,
                                Bitmap.Config.ARGB_8888
                            )

                            originalBitmap.copyPixelsFromBuffer(buffer)

                            bitmap = Bitmap.createBitmap(
                                originalBitmap,
                                0,
                                0,
                                SCREEN_INFO.width,
                                SCREEN_INFO.height
                            )

                            val scaledBitmap = Bitmap.createScaledBitmap(
                                bitmap!!,
                                SCREEN_INFO.width / 2,
                                SCREEN_INFO.height / 2,
                                true
                            )

                            val byteArray = bitmapToByteArray(
                                scaledBitmap,
                                Bitmap.CompressFormat.JPEG,
                                compressQuality
                            )
                            val encodedString =
                                Base64.encodeToString(byteArray, Base64.DEFAULT).trim()
                            // _screenImgData = "data:jpeg;base64,$encodedString"
                            _screenImgData = encodedString
                        }
                    } catch (e: java.lang.Exception) {
                        Log.e(logTag, "acquireLatestImage", e)
                    } finally {
                        bitmap?.recycle()
                    }

                }, serviceHandler)
            }
        Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
        return imageReader?.surface
    }

    override fun onDestroy() {
        checkMediaPermission()
        cancelNotification()
        super.onDestroy()
    }

    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w, h)
        val min = min(w, h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi

                if (isStart) {
                    stopCapture()
                    startCapture()
                }
            }

        }
        sendMessageToActivity(JSONObject().apply {
            put("action", "on_state_changed")
        }.toString())
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): RecordingService = this@RecordingService
    }

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(logTag, "MediaProjection stopped")
            stopCapture()
            destroy()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                mediaProjection?.registerCallback(MediaProjectionCallback(), serviceHandler)

                checkMediaPermission()
                startCapture()
                _isReady = true
            } ?: let {
                Log.d(logTag, "getParcelableExtra intent null, invoke requestMediaProjection")
                requestMediaProjection()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        surface = createSurface()
        startRawVideoRecorder(mediaProjection!!)
        checkMediaPermission()
        _isStart = true

        sendMessageToActivity(JSONObject().apply {
            put("action", "on_screen_recording")
        }.toString())
        return true
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        _isStart = false
        // release video
        if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.surface = null
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        sendMessageToActivity(JSONObject().apply {
            put("action", "on_screen_stopped_recording")
        }.toString())

    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false

        stopCapture()
        _screenImgData = ""
        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }
        mediaProjection?.unregisterCallback(MediaProjectionCallback())
        mediaProjection?.stop()
        mediaProjection = null
        checkMediaPermission()
        stopForeground(true)
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            sendMessageToActivity(JSONObject().apply {
                put("action", "on_state_changed")
            }.toString())
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection) {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return
        }
        createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun getVirtualDisplayFlags(): Int {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface) {
        try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.surface = s
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "CiCy-Agent-VD",
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    SCREEN_INFO.dpi,
                    getVirtualDisplayFlags(),
                    s,
                    null,
                    null
                )
            }
        } catch (e: SecurityException) {
            Log.w(
                logTag,
                "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation"
            )
            requestMediaProjection()
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "CiCyRecording"
            val channelName = "CiCy Recording Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "CiCy Recording Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, RecordingService::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(RECORDING_NOTIFY_TEXT)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent().setComponent(
                        ComponentName(
                            "com.cicy.agent",
                            "com.cicy.agent.app.MainActivity"
                        )
                    )
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(getClientNotifyID(RECORDING_NOTIFY_ID), notification)
    }


    private fun cancelNotification() {
        notificationManager.cancel(getClientNotifyID(RECORDING_NOTIFY_ID))
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun apiPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        Log.d(logTag, "apiPointerInput kind:$kind,x:$x,y:$y,mask:$mask")
        // turn on screen with LIFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LIFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag, "Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }

                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }

                else -> {
                }
            }
        }
    }

    fun handlePostEvent(jsonObject: JSONObject) {
        val eventType = jsonObject["eventType"] as String
        val x = jsonObject.optInt("x", 0)
        val y = jsonObject.optInt("y", 0)
        val value = jsonObject.optInt("value", 0)
        val delta = jsonObject.optInt("delta", 0)
        val text = jsonObject.optString("text", "")
        Log.d(
            logTag,
            "eventType:$eventType, text:$text value: $value, x: $x, y: $y"
        )
        when (eventType) {
            "action" -> {
                InputService.ctx?.onAction(value)
            }

            "swiper" -> {
                InputService.ctx?.swiper(value, x, y, delta)
            }

            "dragStart" -> {
                InputService.ctx?.onMouseInput(LIFT_DOWN, x, y)
            }

            "dragMove" -> {
                InputService.ctx?.onMouseInput(LIFT_MOVE, x, y)
            }

            "dragEnd" -> {
                InputService.ctx?.onMouseInput(LIFT_UP, x, y)
            }

            "click" -> {
                apiPointerInput(0, TOUCH_PAN_START, x, y)
                Handler(Looper.getMainLooper()).postDelayed({
                    apiPointerInput(0, TOUCH_PAN_END, x, y)
                }, 10)
            }

            else -> {
                // Handle unexpected eventType if needed
                Log.e("EventHandler", "Unknown eventType: $eventType")
            }
        }
    }

    private fun sendMessageToActivity(message: String) {
        val intent = Intent("mainMessage")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
