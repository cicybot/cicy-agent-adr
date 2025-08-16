package com.cicy.agent.adr

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.cicy.agent.app.HOME_URL_LOCAL
import com.cicy.agent.app.MainActivity
import com.hjq.permissions.XXPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class MessageActivityHandler(
    private val context: MainActivity,
) : CoroutineScope by MainScope() {
    private fun getContext(): MainActivity {
        return context
    }

    private fun readFileFromAssets(filename: String): String {
        return try {
            val assetManager: AssetManager = context.assets
            val inputStream = assetManager.open(filename)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            content
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }

    private fun getScreenSize(windowManager: WindowManager): Pair<Int, Int> {
        var w = 0
        var h = 0
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
        }
        return Pair(w, h)
    }

    private fun getAgentAppInfo(): JSONObject {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (screenWidth, screenHeight) = getScreenSize(windowManager)
        val ipAddress = NetworkUtils.getCurrentIp(context)
        val brand = Build.BRAND  // 手机品牌
        val model = Build.MODEL  // 手机型号
        val device = Build.DEVICE // 设备名称
        val product = Build.PRODUCT // 产品名称
        val version = Build.VERSION.RELEASE  // 安卓系统版本
        val id = Build.ID  // 编译版本ID
        val abi = getAbi()
        val payload = JSONObject().apply {
            put("abi", abi)
            put("model", model)
            put("inputIsReady", InputService.isReady)
            put("RecordingIsReady", RecordingService.isReady)
            put("width", screenWidth)
            put("height", screenHeight)
            put("dpi", context.resources.displayMetrics.density)
            put("ipAddress", ipAddress)
            put("brand", brand)
            put("model", model)
            put("BuildDevice", device)
            put("BuildProduct", product)
            put("buildVersion", version)
            put("buildId", id)
            put("version", "1.0.1")
        }
        return payload
    }

    private fun onStartInput() {
        if (!InputService.isReady) {
            startAction(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    private fun onStopInput() {
        if (InputService.isReady) {
            InputService.ctx?.disableSelf()
        }
    }

    fun processAsync(messageAsync: String) {
        when (messageAsync) {
            "onStartRecording" -> getContext().startRecording()
            "onStopRecording" -> getContext().stopRecording()
            "onStartInput" -> onStartInput()
            "onStopInput" -> onStopInput()
            else -> {}
        }
    }
    fun execJs(code: String, response: JSONObject, callbackId: String?): JSONObject {
        context.execJs(code) { res ->
            if (callbackId != null) {
                val response = JSONObject().apply {
                    put("res",res)
                }
                val responseIntent = Intent(MessageHandler.ACTION_RESPONSE).apply {
                    putExtra(MessageHandler.EXTRA_CALLBACK_ID, callbackId)
                    putExtra(MessageHandler.EXTRA_RESULT, response.toString())
                }
                getContext().localBroadcastManager.sendBroadcast(responseIntent)
            }
        }
        return response
    }

    fun process(message: String, callbackId: String?): JSONObject {
        var response = JSONObject().apply {
            put("err", "")
        }
        try {
            val json = JSONObject(message)
            val method = json.getString("method")
            val params = json.getJSONArray("params")
            if(method.equals("execJs")){
                return execJs(params.get(0) as String,response,callbackId)
            }

            when (method) {
                "agentAppInfo" -> {
                    response = getAgentAppInfo()
                }

                "onStartRecording" -> getContext().startRecording()
                "onStopRecording" -> getContext().stopRecording()
                "onStartInput" -> {
                    onStartInput()
                }

                "onStopInput" -> {
                    onStopInput()
                }

                "loadHomeURL" -> {
                    context.loadURL(HOME_URL_LOCAL)
                }
                "loadURL" -> {
                    context.loadURL(params.get(0) as String)
                }

                "takeScreenshot" -> {
                    var imgData = ""
                    var imgLen = 0
                    if (RecordingService.isReady) {
                        imgLen = RecordingService.screenImgData.length
                        imgData = "data:image/jpeg;base64,${RecordingService.screenImgData}"
                    }

                    response = JSONObject().apply {
                        put("imgData", imgData)
                        put("imgLen", imgLen)
                    }
                }

                "dumpWindowHierarchy" -> {
                    var xml = ""
                    if (InputService.isReady) {
                        xml = InputService.ctx?.getDumpAsUiAutomatorXml().toString()
                    }
                    response = JSONObject().apply {
                        put("xml", xml)
                    }
                }

                "screenWithXml" -> {
                    var imgData = ""
                    var imgLen = 0
                    if (RecordingService.isReady) {
                        imgLen = RecordingService.screenImgData.length
                        imgData = "data:image/jpeg;base64,${RecordingService.screenImgData}"
                    }

                    var xml = ""
                    if (InputService.isReady) {
                        xml = InputService.ctx?.getDumpAsUiAutomatorXml().toString()
                    }
                    response = JSONObject().apply {
                        put("xml", xml)
                        put("imgData", imgData)
                        put("imgLen", imgLen)
                    }
                }

                "startAction" -> {
                    startAction(context, params.get(0) as String)
                }

                "showToast" -> {
                    Toast.makeText(context, params.get(0) as String, Toast.LENGTH_SHORT).show()
                }

                "getInstalledApps" -> {
                    val isAll = params.optString(0).equals("all")
                    val apps = PackagesList(context).getInstalledApps(isAll)
                    response = JSONObject().apply {
                        put("apps", apps)
                    }
                }

                "checkPermission" -> {
                    val isGranted = XXPermissions.isGranted(context, params.get(0) as String)
                    JSONObject().apply {
                        put("isGranted", isGranted)
                    }
                }

                "requestPermission" -> {
                    requestPermission(context, params.get(0) as String)
                    JSONObject().apply {
                        put("ok", true)
                    }
                }

                "requestNotificationPermission" -> {
                    requestPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    JSONObject().apply {
                        put("ok", true)
                    }
                }

                else -> {
                    response.put("err", "Unknown method: $method")
                }
            }

        } catch (e: Exception) {
            response.put("err", "Invalid message format: ${e.message}")
        }
        if (callbackId !== null) {

            val responseIntent = Intent(MessageHandler.ACTION_RESPONSE).apply {
                putExtra(MessageHandler.EXTRA_CALLBACK_ID, callbackId)
                putExtra(MessageHandler.EXTRA_RESULT, response.toString())
            }
            getContext().localBroadcastManager.sendBroadcast(responseIntent)
        }
        return response
    }
}