package com.cicy.agent.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cicy.agent.R
import com.cicy.agent.adr.ACT_REQUEST_MEDIA_PROJECTION
import com.cicy.agent.adr.LocalServer
import com.cicy.agent.adr.MessageActivityHandler
import com.cicy.agent.adr.MessageHandler
import com.cicy.agent.adr.PermissionRequestTransparentActivity
import com.cicy.agent.adr.REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION
import com.cicy.agent.adr.RES_FAILED
import com.cicy.agent.adr.RecordingService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val logTag = "mMainActivity"
    private var webviewIsReady: Boolean = false

    var recordingService: RecordingService? = null

    lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var messageHandler: MessageActivityHandler


    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(
                    RequestPermission()
                ) { _: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContentView(R.layout.activity_main)
        ContextCompat.startForegroundService(this, Intent(this, LocalServer::class.java))
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(
            serviceRequestReceiver,
            IntentFilter(MessageHandler.ACTION_REQUEST)
        )
        messageHandler = MessageActivityHandler(this)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(this), "__AndroidAPI")
    }

    fun loadURL(url: String){
        webView.loadUrl(url)
    }

    fun execJs(code: String, callback: (String) -> Unit) {
        webView.evaluateJavascript(code) { result ->
            callback(result)
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume")
        onStateChanged()
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        recordingService?.let {
            unbindService(serviceConnection)
        }

        localBroadcastManager.unregisterReceiver(serviceRequestReceiver)

        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        Log.d(logTag, "onStop")
    }

    override fun onStart() {
        super.onStart()
        Log.d(logTag, "onStart")
    }

    fun sendMessageToWebView(message: String) {
        if (webviewIsReady) {
            webView.post {
                webView.evaluateJavascript(
                    "javascript:AppCallback(${JSONObject.quote(message)})",
                    null
                )
            }
        }
    }

    fun onStateChanged() {
        val message = JSONObject().apply {
            put("action", "on_state_changed")
        }.toString()
        sendMessageToWebView(message)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            sendMessageToWebView(JSONObject().apply {
                put("action", "on_media_projection_canceled")
            }.toString())
        }
        onStateChanged()
    }


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, serviceBinder: IBinder?) {
            val binder = serviceBinder as RecordingService.LocalBinder
            recordingService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
        }
    }

    fun startRecording() {
        if (!RecordingService.isReady) {
            Intent(this, RecordingService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            requestMediaProjection()
        }
    }

    fun stopRecording() {
        recordingService?.destroy()
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(
            intent,
            REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION
        )
    }

    @CallSuper
    open fun updateRecordingStatus(state: String) {
    }

    private val serviceRequestReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MessageHandler.ACTION_REQUEST) return
            val messageAsync = intent.getStringExtra(MessageHandler.EXTRA_MESSAGE_ASYNC)
            if (messageAsync !== null) {
                messageHandler.processAsync(messageAsync)
                when (messageAsync) {
                    "onServerStarted"->{
                        webView.loadUrl(HOME_URL_LOCAL)
                    }
                    "on_screen_recording" -> updateRecordingStatus(messageAsync)
                    "on_recording_state_changed" -> updateRecordingStatus(messageAsync)
                    "on_screen_stopped_recording" -> updateRecordingStatus(messageAsync)
                }
                sendMessageToWebView(JSONObject().apply {
                    put("action", messageAsync)
                }.toString())
            }
            val message = intent.getStringExtra(MessageHandler.EXTRA_MESSAGE)
            if (message !== null) {
                val callbackId = intent.getStringExtra(MessageHandler.EXTRA_CALLBACK_ID)
                messageHandler.process(message, callbackId)
            }
        }
    }
}
