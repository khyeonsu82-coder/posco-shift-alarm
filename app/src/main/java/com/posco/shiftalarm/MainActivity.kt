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
            userAgentString = userAgentString + " PoscoShiftAlarm/1.2"
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

    fun canUseFullScreen(): Boolean = Perms.canUseFullScreen(this)

    fun canDrawOverlay(): Boolean = Perms.canDrawOverlay(this)

    fun isIgnoringBattery(): Boolean = Perms.isIgnoringBattery(this)

    /**
     * 잠금화면 알람에 필요한 권한을 하나씩 안내한다.
     * (알림 → 정확한 알람 → 잠금화면 전체화면 알림 → 다른 앱 위에 표시 → 배터리 최적화 예외)
     */
    fun requestAlarmPermissions() {
        if (needsNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
            Perms.openExactAlarmSettings(this)
            return
        }
        if (!canUseFullScreen()) {
            Perms.openFullScreenSettings(this)
            return
        }
        if (!canDrawOverlay()) {
            Perms.openOverlaySettings(this)
            return
        }
        if (!isIgnoringBattery()) {
            Perms.openBatterySettings(this)
        }
    }

    fun openFullScreenSettings() {
        Perms.openFullScreenSettings(this)
    }

    fun openOverlaySettings() {
        Perms.openOverlaySettings(this)
    }

    fun openExactAlarmSettings() {
        Perms.openExactAlarmSettings(this)
    }

    fun openNotificationSettings() {
        Perms.openNotificationSettings(this)
    }

    fun openBatterySettings() {
        Perms.openBatterySettings(this)
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
            o.put("version", "1.2")
            o.put("scheduled", upcoming.size)
            o.put("next", upcoming.firstOrNull()?.ts ?: 0L)
            o.put("nextTime", upcoming.firstOrNull()?.time ?: "")
            o.put("nextLabel", upcoming.firstOrNull()?.label ?: "")
            o.put("lastSync", AlarmStore.lastSync(this))
            o.put("exact", AlarmScheduler.canScheduleExact(this))
            o.put("notifications", NotificationManagerCompat.from(this).areNotificationsEnabled())
            o.put("fullScreen", canUseFullScreen())
            o.put("overlay", canDrawOverlay())
            o.put("battery", isIgnoringBattery())
            o.put("channel", Perms.applyNotificationChannelState(this))
            o.put("snooze", AlarmStore.loadSnooze(this)?.ts ?: 0L)
        } catch (e: Exception) {
        }
        return o.toString()
    }
}
