package com.posco.shiftalarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIFICATIONS = 1001
        private const val URL_APP = "file:///android_asset/www/index.html"
        private const val PREF_UI = "shift_alarm_ui"
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.parseColor("#0B0F17"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " PoscoShiftAlarm/1.0"
        }
        web.addJavascriptInterface(NativeAlarmBridge(this), "NativeAlarm")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url: Uri = request.url
                if (url.scheme == "file") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                notifyWeb()
            }
        }

        web.loadUrl(URL_APP)
        maybeAskNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        notifyWeb()
    }

    /** 권한 상태가 바뀌었을 수 있으니 웹 UI 를 새로 고치고 알람을 다시 등록한다. */
    private fun notifyWeb() {
        try {
            AlarmScheduler.scheduleAll(this)
        } catch (e: Exception) {
        }
        try {
            web.evaluateJavascript(
                "try{if(window.__nativeRefresh)window.__nativeRefresh();}catch(e){}", null
            )
        } catch (e: Exception) {
        }
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!needsNotificationPermission()) return
        val prefs = getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun canUseFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.canUseFullScreenIntent()
            } catch (e: Exception) {
                true
            }
        } else true

    fun isIgnoringBattery(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } catch (e: Exception) {
                false
            }
        } else true

    /**
     * 잠금화면 알람에 필요한 권한을 하나씩 안내한다.
     * (알림 → 정확한 알람 → 전체화면 알림 → 배터리 최적화 예외)
     */
    fun requestAlarmPermissions() {
        if (needsNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
            openSystemSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, withPackage = true)
            return
        }
        if (!canUseFullScreen()) {
            if (Build.VERSION.SDK_INT >= 34) {
                openSystemSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBattery()) {
            openSystemSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
        }
    }

    fun openBatterySettings() {
        openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, withPackage = false)
    }

    private fun openSystemSettings(action: String, withPackage: Boolean) {
        try {
            val i = Intent(action)
            if (withPackage) i.data = Uri.parse("package:$packageName")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            notifyWeb()
        }
    }

    override fun onDestroy() {
        try {
            web.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    /** 웹(설정 시트)에서 보여 줄 네이티브 상태. */
    fun statusJson(): String {
        val now = System.currentTimeMillis()
        val upcoming = AlarmStore.load(this).filter { it.ts > now }.sortedBy { it.ts }
        val o = JSONObject()
        try {
            o.put("native", true)
            o.put("version", "1.1")
            o.put("scheduled", upcoming.size)
            o.put("next", upcoming.firstOrNull()?.ts ?: 0L)
            o.put("nextTime", upcoming.firstOrNull()?.time ?: "")
            o.put("nextLabel", upcoming.firstOrNull()?.label ?: "")
            o.put("lastSync", AlarmStore.lastSync(this))
            o.put("exact", AlarmScheduler.canScheduleExact(this))
            o.put("notifications", NotificationManagerCompat.from(this).areNotificationsEnabled())
            o.put("fullScreen", canUseFullScreen())
            o.put("battery", isIgnoringBattery())
            o.put("snooze", AlarmStore.loadSnooze(this)?.ts ?: 0L)
        } catch (e: Exception) {
        }
        return o.toString()
    }
}
