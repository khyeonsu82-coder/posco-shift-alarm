package com.posco.shiftalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 알람이 울리는 동안의 소리·진동·전체화면 알림을 담당하는 포그라운드 서비스.
 *
 * v1.2 잠금화면 즉시 표시 3단계:
 *  1) fullScreenIntent 알림 (정상 경로 — 잠금화면 위 전체화면 AlarmActivity)
 *  2) 1.2초 뒤에도 알람 화면이 안 떴으면(startActivity 직접 호출) — 안드로이드 14에서
 *     USE_FULL_SCREEN_INTENT 가 막혀 있어도 정확한 알람 직후의 임시 허용/다른 앱 위에 표시 권한으로 뜬다
 *  3) 그래도 안 뜨면(다른 앱 위에 표시 권한이 있을 때) AlarmOverlay 로 잠금화면 위에 직접 그린다
 */
class AlarmService : Service() {

    companion object {
        const val CHANNEL_ID = "shift_alarm_channel_v1"
        const val NOTIFICATION_ID = 4711

        const val ACTION_START = "com.posco.shiftalarm.START_ALARM"
        const val ACTION_STOP = "com.posco.shiftalarm.STOP_ALARM"
        const val ACTION_SNOOZE = "com.posco.shiftalarm.SNOOZE_ALARM"

        private const val AUTO_STOP_MS = 10 * 60 * 1000L
        private const val RETRY_LOCKSCREEN_MS = 1200L

        fun start(ctx: Context, alarm: ShiftAlarm) {
            val i = Intent(ctx, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
                putExtra(AlarmScheduler.EXTRA_DATE, alarm.date)
                putExtra(AlarmScheduler.EXTRA_TIME, alarm.time)
                putExtra(AlarmScheduler.EXTRA_LABEL, alarm.label)
                putExtra(AlarmScheduler.EXTRA_TS, alarm.ts)
            }
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                try {
                    ctx.startService(i)
                } catch (e2: Exception) {
                }
            }
        }

        fun dismiss(ctx: Context) {
            val i = Intent(ctx, AlarmService::class.java).setAction(ACTION_STOP)
                .setData(android.net.Uri.parse("shiftalarm://cmd/stop"))
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                // 백그라운드 서비스 시작이 막혔으면(오버레이에서 끄는 경우 등) 브로드캐스트로 알린다
                try {
                    ctx.sendBroadcast(
                        Intent(ACTION_STOP).setPackage(ctx.packageName)
                    )
                } catch (e2: Exception) {
                }
                // 서비스가 이미 내려갔으면 알림만 지운다
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIFICATION_ID)
                } catch (e3: Exception) {
                }
            }
            AlarmOverlay.hide()
        }

        /** 잠금화면 위 알람 화면을 직접 띄워 본다(백그라운드 제한에 걸리면 조용히 실패). */
        fun launchAlarmUi(ctx: Context, alarm: ShiftAlarm): Boolean {
            val i = Intent(ctx, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
                putExtra(AlarmScheduler.EXTRA_DATE, alarm.date)
                putExtra(AlarmScheduler.EXTRA_TIME, alarm.time)
                putExtra(AlarmScheduler.EXTRA_LABEL, alarm.label)
                putExtra(AlarmScheduler.EXTRA_TS, alarm.ts)
            }
            return try {
                ctx.startActivity(i)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm() }
    private var current: ShiftAlarm? = null
    private var screenReceiverOn = false

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_STOP) stopAlarm()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            // 화면이 늦게 켜졌거나 잠금이 풀리면 알람 화면을 다시 올린다
            val a = current ?: return
            if (AlarmActivity.onScreen) return
            launchAlarmUi(this@AlarmService, a)
        }
    }

    private val fallbackRunnable = object : Runnable {
        override fun run() {
            val a = current ?: return
            if (AlarmActivity.onScreen || AlarmOverlay.isShowing()) return
            // 2단계: 직접 startActivity
            launchAlarmUi(this@AlarmService, a)
            handler.postDelayed({
                // 3단계: 그래도 안 떴으면 오버레이(다른 앱 위에 표시)
                if (!AlarmActivity.onScreen && current != null) {
                    AlarmOverlay.show(this@AlarmService, a)
                }
            }, 700L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWake()
        registerCmdReceiver()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                val a = alarmFrom(intent)
                if (a != null) {
                    try {
                        AlarmScheduler.snooze(this, a)
                    } catch (e: Exception) {
                    }
                }
                stopAlarm()
                return START_NOT_STICKY
            }
            else -> {
                val a = alarmFrom(intent)
                if (a == null) {
                    stopAlarm()
                    return START_NOT_STICKY
                }
                current = a
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(a))
                } catch (e: Exception) {
                    // 알림 권한이 없어도 소리는 계속 울린다
                }
                startRinging()
                handler.removeCallbacks(autoStop)
                handler.postDelayed(autoStop, AUTO_STOP_MS)
                ensureLockScreenUi(a)
            }
        }
        return START_NOT_STICKY
    }

    /** 알람 화면이 실제로 떴는지 몇 번 확인하고, 안 떴으면 직접 띄우기 → 오버레이로 내려간다. */
    private fun ensureLockScreenUi(a: ShiftAlarm) {
        handler.removeCallbacks(fallbackRunnable)
        // 정확한 알람 직후의 임시 허용 시간 안에서 먼저 한 번 시도한다
        launchAlarmUi(this, a)
        handler.postDelayed(fallbackRunnable, RETRY_LOCKSCREEN_MS)
    }

    private fun alarmFrom(intent: Intent?): ShiftAlarm? {
        if (intent == null) return null
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return null
        val ts = intent.getLongExtra(AlarmScheduler.EXTRA_TS, 0L).let {
            if (it > 0L) it else System.currentTimeMillis()
        }
        return ShiftAlarm(
            id = id,
            date = intent.getStringExtra(AlarmScheduler.EXTRA_DATE) ?: "",
            time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME) ?: TimeFormat.hm(ts),
            label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "알람",
            ts = ts
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        ch.description = getString(R.string.alarm_channel_desc)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.enableVibration(false)
        ch.setSound(null, null)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun piFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun fullScreenIntent(a: ShiftAlarm): PendingIntent {
        val i = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmScheduler.EXTRA_ID, a.id)
            putExtra(AlarmScheduler.EXTRA_DATE, a.date)
            putExtra(AlarmScheduler.EXTRA_TIME, a.time)
            putExtra(AlarmScheduler.EXTRA_LABEL, a.label)
            putExtra(AlarmScheduler.EXTRA_TS, a.ts)
        }
        return PendingIntent.getActivity(this, a.id.hashCode() and 0x7fffffff, i, piFlags())
    }

    private fun serviceIntent(action: String, a: ShiftAlarm): Intent =
        Intent(this, AlarmService::class.java).apply {
            this.action = action
            data = android.net.Uri.parse("shiftalarm://cmd/" + action + "/" + android.net.Uri.encode(a.id))
            putExtra(AlarmScheduler.EXTRA_ID, a.id)
            putExtra(AlarmScheduler.EXTRA_DATE, a.date)
            putExtra(AlarmScheduler.EXTRA_TIME, a.time)
            putExtra(AlarmScheduler.EXTRA_LABEL, a.label)
            putExtra(AlarmScheduler.EXTRA_TS, a.ts)
        }

    private fun buildNotification(a: ShiftAlarm): Notification {
        val fs = fullScreenIntent(a)
        val stopPi = PendingIntent.getService(this, 11, serviceIntent(ACTION_STOP, a), piFlags())
        val snoozePi = PendingIntent.getService(this, 12, serviceIntent(ACTION_SNOOZE, a), piFlags())

        val body = if (Perms.canUseFullScreen(this)) {
            a.time + " · 지금 일어날 시간이에요"
        } else {
            a.time + " · 잠금화면 알림이 꺼져 있어요(설정에서 켜기)"
        }

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.notif_title) + " " + a.label)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(fs)
            .setFullScreenIntent(fs, true)
            .addAction(0, getString(R.string.action_snooze), snoozePi)
            .addAction(0, getString(R.string.action_stop), stopPi)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 즉시 표시(포그라운드 서비스 알림의 10초 지연 방지)
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "posco:shift_alarm_ring")
            wakeLock?.acquire(15 * 60 * 1000L)
        } catch (e: Exception) {
            wakeLock = null
        }
    }

    private fun registerCmdReceiver() {
        try {
            ContextCompat.registerReceiver(
                this,
                cmdReceiver,
                IntentFilter(ACTION_STOP),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverOn) return
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // 시스템 브로드캐스트라 EXPORTED 로 등록해야 전달된다(보호된 액션이라 위조는 불가)
            ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_EXPORTED)
            screenReceiverOn = true
        } catch (e: Exception) {
            screenReceiverOn = false
        }
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                val r = RingtoneManager.getRingtone(applicationContext, uri)
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    r.isLooping = true
                }
                r.play()
                ringtone = r
            }
        } catch (e: Exception) {
            // 무음 모드·벨소리 파일 없음 등은 무시(진동은 계속)
        }

        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = v
            val pattern = longArrayOf(0L, 800L, 500L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            // 진동 미지원 기기 무시
        }
    }

    private fun stopAlarm() {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(fallbackRunnable)
        current = null
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        vibrator = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        AlarmOverlay.hide()
        AlarmActivity.closeAll(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(fallbackRunnable)
        AlarmOverlay.hide()
        if (screenReceiverOn) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
            }
            screenReceiverOn = false
        }
        try {
            unregisterReceiver(cmdReceiver)
        } catch (e: Exception) {
        }
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
