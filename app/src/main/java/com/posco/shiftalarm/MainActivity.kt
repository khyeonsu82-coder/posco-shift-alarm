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

    /** 권한 안내를 이어서 진행할 때의 대기 목록·현재 항목 */
    private val permWalk = ArrayDeque<String>()
    private var awaitingPerm: String? = null

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
            userAgentString = userAgentString + " PoscoShiftAlarm/1.4"
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
        // 알림 채널을 미리 만들어 둔다(설정 화면에서 중요도까지 확인할 수 있게)
        try {
            AlarmService.ensureChannel(this)
        } catch (e: Exception) {
        }
        maybeAskNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 권한 안내를 여러 개 이어서 진행 중이면, 방금 열어 둔 설정을 켜고 돌아왔는지 확인해
        // 다음 항목으로 넘어간다(v1.3 — 한 번에 하나씩만 열어 주던 v1.2 는 5번 눌러야 했다)
        val awaiting = awaitingPerm
        if (awaiting != null && isPermSatisfied(awaiting)) {
            awaitingPerm = null
            openNextPermission()
            return
        }
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
     * 잠금화면 알람에 필요한 권한을 **한 번 누르면 순서대로** 안내한다(v1.3).
     * (알림 → 정확한 알람 → 잠금화면 전체화면 알림 → 다른 앱 위에 표시 → 배터리 최적화 예외)
     * 설정 화면에서 켜고 돌아오면 onResume 이 다음 항목을 이어서 연다.
     */
    fun requestAlarmPermissions() {
        permWalk.clear()
        permWalk.addAll(missingPermissions())
        awaitingPerm = null
        openNextPermission()
    }

    private fun missingPermissions(): List<String> {
        val out = ArrayList<String>()
        if (needsNotificationPermission()) out.add("notif")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
            out.add("exact")
        }
        if (!canUseFullScreen()) out.add("fullscreen")
        if (!canDrawOverlay()) out.add("overlay")
        if (!isIgnoringBattery()) out.add("battery")
        return out
    }

    private fun isPermSatisfied(key: String): Boolean = when (key) {
        "notif" -> !needsNotificationPermission()
        "exact" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S || AlarmScheduler.canScheduleExact(this)
        "fullscreen" -> canUseFullScreen()
        "overlay" -> canDrawOverlay()
        "battery" -> isIgnoringBattery()
        else -> true
    }

    private fun openNextPermission() {
        while (true) {
            val key = permWalk.removeFirstOrNull()
            if (key == null) {
                awaitingPerm = null
                notifyWeb()
                return
            }
            if (isPermSatisfied(key)) continue
            awaitingPerm = key
            when (key) {
                "notif" -> requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATIONS
                )
                "exact" -> Perms.openExactAlarmSettings(this)
                "fullscreen" -> Perms.openFullScreenSettings(this)
                "overlay" -> Perms.openOverlaySettings(this)
                "battery" -> Perms.openBatterySettings(this)
            }
            return
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
            // 알림 권한을 켰으면 다음 항목으로 이어서 안내한다
            if (awaitingPerm == "notif" && !needsNotificationPermission()) {
                awaitingPerm = null
                openNextPermission()
            } else {
                notifyWeb()
            }
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
            o.put("version", "1.4")
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
            o.put("channelImportance", Perms.channelImportance(this))
            o.put("channelSound", Perms.channelHasSound(this))
            o.put("lockscreen", Perms.lockscreenNotificationsEnabled(this))
            o.put("snooze", AlarmStore.loadSnooze(this)?.ts ?: 0L)
            // 마지막 알람이 실제로 어떻게 떴는지(진단)
            o.put("last", AlarmLog.json(this))
        } catch (e: Exception) {
        }
        return o.toString()
    }
}
