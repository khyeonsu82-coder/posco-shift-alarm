package com.posco.shiftalarm

import android.app.Notification
import android.app.NotificationChannel
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 알람이 울리는 동안의 소리·진동·알림·잠금화면 화면을 담당하는 포그라운드 서비스.
 *
 * v1.4 에서 바꾼 것:
 *  1) **테스트 알람은 진동만** — `10초 뒤 테스트 알람` 은 벨소리 없이 4초짜리 짧은 진동만 울리고
 *     화면이 떠 있는 60초 뒤 스스로 꺼진다. 실제 출근 알람은 기존대로 알람음 반복 + 진동.
 *  2) **알람 채널 v3** — 채널 소리를 `null` 이 아니라 **무음 리소스**로 설정했다.
 *     삼성 One UI 는 소리·진동이 모두 없는 "무음 채널"의 알림을 전체화면 알림으로 승격하지 않으므로,
 *     비어 있지 않은 소리를 가진 알람 채널로 만들어 FSI 승격 조건을 만족시킨다(들리는 소리는 없음).
 *  3) **알람 화면을 먼저 직접 띄운다** — AlarmReceiver 가 `startActivity()` 로 바로 열고,
 *     서비스는 그 화면이 안 떴을 때만 PendingIntent(직접실행/전체화면알림 교대)로 재시도한다.
 *  4) 잠금화면 화면이 뜨면 소리·진동은 화면(AlarmActivity)이 서비스를 시작해도 이어진다(중복 시작 방지).
 *
 * v2.0 에서 바꾼 것:
 *  1) 알람마다 **울림 방식** — `mode = "vibrate"` 는 벨소리 없이 진동만, 해제할 때까지 반복한다.
 *  2) **알람음 선택 + 점점 크게** — 설정에서 고른 알람음(폰의 알람음·알림음)을 MediaPlayer 로 재생하고,
 *     "점점 크게" 가 켜져 있으면 8초 동안 15% → 100% 로 올린다.
 *  3) 백그라운드 6시간 동기화가 계산한 목록도 같은 방식으로 예약된다.
 *
 * v1.3 에서 바꾼 것 (v1.2 는 잠금화면에 알림 카드만 떴다):
 *  · 알림 ID 를 발화마다 새로 붙여 시스템이 "새 알림"으로 FSI 판단을 하게 했다.
 *  · `notify()` → `startForeground` 순서, 끄기·스누즈 액션, 스와이프로 끄기(deleteIntent).
 */
class AlarmService : Service() {

    companion object {
        /** v1.4 채널. 예전 채널은 삭제해 중요도가 낮아진 설정이 따라오지 않게 한다. */
        const val CHANNEL_ID = "shift_alarm_channel_v3"
        private val LEGACY_CHANNEL_IDS = arrayOf("shift_alarm_channel_v1", "shift_alarm_channel_v2")
        private const val LEGACY_NOTIFICATION_ID = 4711
        private const val OLD_NOTIF_KEY = "last_notif_id_v1"

        const val ACTION_START = "com.posco.shiftalarm.START_ALARM"
        const val ACTION_STOP = "com.posco.shiftalarm.STOP_ALARM"
        const val ACTION_SNOOZE = "com.posco.shiftalarm.SNOOZE_ALARM"

        const val EXTRA_STOP_REASON = "stop_reason"

        private const val AUTO_STOP_MS = 10 * 60 * 1000L
        /** 테스트 알람은 진동이 끝나면 볼일이 없으니 1분 뒤 스스로 끈다. */
        private const val TEST_AUTO_STOP_MS = 60 * 1000L
        /** 테스트 알람 진동 길이(ms). 벨소리는 내지 않는다. */
        private const val TEST_BUZZ_MS = 4_000L
        /** 잠금화면 화면이 안 뜰 때 다시 시도하는 간격(ms). */
        private val RETRY_MS = longArrayOf(900L, 2000L, 4000L)

        /** 지금 울리고 있는 알람의 ts(없으면 0). 화면이 서비스를 중복 시작하지 않게 한다. */
        @Volatile
        var activeTs: Long = 0L
            private set

        fun start(ctx: Context, alarm: ShiftAlarm) {
            ensureChannel(ctx)
            val i = Intent(ctx, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
                putExtra(AlarmScheduler.EXTRA_DATE, alarm.date)
                putExtra(AlarmScheduler.EXTRA_TIME, alarm.time)
                putExtra(AlarmScheduler.EXTRA_LABEL, alarm.label)
                putExtra(AlarmScheduler.EXTRA_TS, alarm.ts)
                putExtra(AlarmScheduler.EXTRA_MODE, ShiftAlarm.modeOf(alarm.mode))
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

        /** 알람 채널을 미리 만들어 둔다(설정 화면에서 중요도를 바로 확인할 수 있게). */
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                // 예전 채널(중요도가 낮아졌을 수 있음)을 지워 새 채널이 HIGH 로 다시 만들어지게 한다
                for (legacy in LEGACY_CHANNEL_IDS) {
                    try {
                        nm.deleteNotificationChannel(legacy)
                    } catch (e: Exception) {
                    }
                }
                if (nm.getNotificationChannel(CHANNEL_ID) != null) return
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
                ch.description = ctx.getString(R.string.alarm_channel_desc)
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                ch.enableVibration(false)
                // 무음 리소스(들리지 않는 0.25초) — 소리 없는 채널은 삼성 One UI 가 전체화면 알림으로
                // 승격하지 않는다. 채널에 소리를 두되 들리지 않게 하고, 실제 울림은 서비스가 낸다.
                try {
                    ch.setSound(
                        silentSoundUri(ctx),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } catch (e: Exception) {
                    ch.setSound(null, null)
                }
                ch.enableLights(true)
                ch.lightColor = 0xFFFFC44D.toInt()
                ch.setShowBadge(true)
                // 방해 금지 모드에서도 알람은 울려야 한다(사용자가 알림 정책 접근을 허용한 경우에만).
                try {
                    if (nm.isNotificationPolicyAccessGranted) ch.setBypassDnd(true)
                } catch (e: Exception) {
                }
                nm.createNotificationChannel(ch)
            } catch (e: Exception) {
            }
        }

        /** 채널 소리로 쓸 무음 리소스 주소. */
        fun silentSoundUri(ctx: Context): Uri =
            Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + ctx.packageName + "/" + R.raw.silent)

        fun dismiss(ctx: Context, reason: String = "화면") {
            val i = Intent(ctx, AlarmService::class.java).setAction(ACTION_STOP)
                .setData(Uri.parse("shiftalarm://cmd/stop"))
                .putExtra(EXTRA_STOP_REASON, reason)
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                // 백그라운드 서비스 시작이 막혔으면(오버레이에서 끄는 경우 등) 브로드캐스트로 알린다
                try {
                    ctx.sendBroadcast(Intent(ACTION_STOP).setPackage(ctx.packageName))
                } catch (e2: Exception) {
                }
                // 서비스가 이미 내려갔으면 알림만 지운다
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(LEGACY_NOTIFICATION_ID)
                    val saved = ctx.getSharedPreferences("shift_alarm_svc", Context.MODE_PRIVATE)
                        .getInt(OLD_NOTIF_KEY, 0)
                    if (saved != 0) nm.cancel(saved)
                } catch (e3: Exception) {
                }
                activeTs = 0L
                AlarmLog.end(ctx, reason)
            }
            AlarmOverlay.hide()
        }
    }

    private var ringtone: Ringtone? = null
    private var player: MediaPlayer? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var fadeRunnable: Runnable? = null
    private var vibrator: Vibrator? = null
    private var ringWake: PowerManager.WakeLock? = null
    private var screenWake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm("자동") }
    private var current: ShiftAlarm? = null
    private var screenReceiverOn = false
    private var retryIndex = 0
    private var notifId = LEGACY_NOTIFICATION_ID

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_STOP) stopAlarm("알림")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            // 화면이 늦게 켜졌거나 잠금이 풀리면 알람 화면을 다시 올린다
            val a = current ?: return
            if (AlarmActivity.onScreen) return
            AlarmUi.launch(this@AlarmService, a, viaPi = false)
        }
    }

    /** 잠금화면 화면이 안 떴을 때: 다시 시도(직접 실행/PendingIntent 교대) → 오버레이 → 화면 켜기. */
    private val retryRunnable = object : Runnable {
        override fun run() {
            val a = current ?: return
            if (AlarmActivity.onScreen) return
            // 홀수 번째는 PendingIntent(전체화면 알림 경로), 짝수 번째는 직접 startActivity 로 시도한다
            AlarmUi.launch(this@AlarmService, a, viaPi = retryIndex % 2 == 1)
            if (retryIndex >= 1 && !AlarmActivity.onScreen && !AlarmOverlay.isShowing()) {
                // 잠금이 걸려 있지 않을 때만 오버레이가 의미가 있다(보안 잠금화면 위에는 못 그린다)
                if (!isSecureLocked()) {
                    if (AlarmOverlay.show(this@AlarmService, a)) {
                        AlarmLog.overlayShown(this@AlarmService)
                    }
                }
            }
            if (retryIndex >= RETRY_MS.size - 1) {
                // 마지막 수단: 화면이 꺼져 있으면 켜서 알림 카드라도 눈에 보이게 한다
                wakeScreen()
            }
            retryIndex++
            if (retryIndex < RETRY_MS.size) {
                handler.postDelayed(this, RETRY_MS[retryIndex])
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerCmdReceiver()
        registerScreenReceiver()
        // 화면을 깨우는 WakeLock 은 여기서 잡지 않는다(전체화면 알림이 강등될 수 있다).
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm(intent.getStringExtra(EXTRA_STOP_REASON) ?: "알림")
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
                stopAlarm("알림")
                return START_NOT_STICKY
            }
            else -> {
                val a = alarmFrom(intent)
                if (a == null) {
                    stopAlarm("자동")
                    return START_NOT_STICKY
                }
                // 화면(AlarmActivity)이 먼저 이 알람으로 서비스를 시작했으면 두 번 울리지 않는다
                if (current != null && current?.ts == a.ts) return START_NOT_STICKY
                current = a
                startAlarm(a)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 알람 하나를 시작한다.
     * 순서가 중요하다: ① 알림 발행(일반 알림으로 '새 알림' 등록 → FSI 판단)
     * → ② startForeground → ③ 소리·진동 → ④ 잠금화면 화면 시도.
     *
     * 알림 권한이 없어도 포그라운드 서비스 알림은 표시되도록 notify 를 직접 부르므로
     * lint 의 권한 검사는 끈다(실패는 try-catch 로 삼킨다).
     */
    @SuppressLint("MissingPermission")
    private fun startAlarm(a: ShiftAlarm) {
        // 이전 알람이 아직 울리는 중이면(스누즈·연속 발화) 그 소리·진동을 먼저 끊는다
        releaseSound()
        activeTs = a.ts
        AlarmLog.start(this, a)
        notifId = AlarmUi.notifId(a.ts)
        rememberNotifId(notifId)
        val notif = buildNotification(a)

        cancelStaleNotifications()

        // ① 일반 알림으로 먼저 올린다 — 같은 ID 로 startForeground 하면 '갱신'이 되어
        //    전체화면 알림 판단이 다시 돌지 않는 기기가 있다.
        try {
            NotificationManagerCompat.from(this).notify(notifId, notif)
        } catch (e: Exception) {
        }

        // ② 포그라운드 승격 (알림 권한이 없어도 여기서는 표시된다)
        try {
            ServiceCompat.startForeground(
                this,
                notifId,
                notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        } catch (e: Exception) {
            // 알림은 이미 올라갔으므로 소리·진동은 계속 진행한다.
            try {
                startForeground(notifId, notif)
            } catch (e2: Exception) {
            }
        }

        // ③ 소리·진동 — 테스트 알람은 짧은 진동만, 진동 전용 알람은 진동만
        if (a.isTest()) startTestBuzz() else startRinging(a)

        // ④ 잠금화면 위 알람 화면 — 즉시 한 번, 안 뜨면 재시도
        ensureLockScreenUi(a)

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, if (a.isTest()) TEST_AUTO_STOP_MS else AUTO_STOP_MS)
    }

    private fun ensureLockScreenUi(a: ShiftAlarm) {
        handler.removeCallbacks(retryRunnable)
        retryIndex = 0
        AlarmUi.launch(this, a, viaPi = false)
        handler.postDelayed(retryRunnable, RETRY_MS[0])
    }

    private fun isSecureLocked(): Boolean = try {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        km.isKeyguardLocked && km.isDeviceSecure
    } catch (e: Exception) {
        false
    }

    /** 화면이 꺼져 있을 때만, 알림 발행이 끝난 뒤에 화면을 켠다(마지막 수단). */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            if (screenWake == null) {
                screenWake = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "posco:shift_alarm_screen"
                )
            }
            screenWake?.acquire(60_000L)
        } catch (e: Exception) {
        }
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
            ts = ts,
            mode = ShiftAlarm.modeOf(intent.getStringExtra(AlarmScheduler.EXTRA_MODE))
        )
    }

    private fun createChannel() {
        ensureChannel(this)
    }

    private fun piFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun serviceIntent(action: String, a: ShiftAlarm): Intent =
        Intent(this, AlarmService::class.java).apply {
            this.action = action
            data = Uri.parse("shiftalarm://cmd/" + action + "/" + Uri.encode(a.id))
            putExtra(AlarmScheduler.EXTRA_ID, a.id)
            putExtra(AlarmScheduler.EXTRA_DATE, a.date)
            putExtra(AlarmScheduler.EXTRA_TIME, a.time)
            putExtra(AlarmScheduler.EXTRA_LABEL, a.label)
            putExtra(AlarmScheduler.EXTRA_TS, a.ts)
            putExtra(AlarmScheduler.EXTRA_MODE, ShiftAlarm.modeOf(a.mode))
        }

    private fun stopActionPi(a: ShiftAlarm, code: Int, reason: String): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            serviceIntent(ACTION_STOP, a).putExtra(EXTRA_STOP_REASON, reason),
            piFlags()
        )

    private fun buildNotification(a: ShiftAlarm): Notification {
        val open = AlarmUi.pendingIntent(this, a)
        val stopPi = stopActionPi(a, 11, "알림")
        val swipePi = stopActionPi(a, 13, "스와이프")
        val snoozePi = PendingIntent.getService(this, 12, serviceIntent(ACTION_SNOOZE, a), piFlags())

        val body = when {
            a.isTest() -> a.time + " · 테스트 알람(진동만 울려요)"
            a.isVibrateOnly() -> a.time + " · 진동만 울리는 알람이에요"
            Perms.canUseFullScreen(this) -> a.time + " · 지금 일어날 시간이에요"
            else -> a.time + " · 잠금화면 전체화면 알림이 꺼져 있어요(설정에서 켜기)"
        }
        val big = body + "\n" + getString(R.string.notif_big_hint)

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(0xFFFFC44D.toInt())
            .setContentTitle(getString(R.string.notif_title) + " " + a.label)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 잠금화면에서도 그대로 보이고, 스와이프로도 끌 수 있게 ongoing 은 끈다.
            .setOngoing(false)
            .setAutoCancel(false)
            .setShowWhen(false)
            // 무음 플래그(setSilent) 는 쓰지 않는다 — 삼성 One UI 에서 전체화면 승격이 막힌다.
            // 소리는 서비스가 USAGE_ALARM 으로 직접 내고, 채널에는 들리지 않는 소리만 두었다.
            .setDefaults(0)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setDeleteIntent(swipePi)
            .addAction(0, getString(R.string.action_snooze), snoozePi)
            .addAction(0, getString(R.string.action_stop), stopPi)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun rememberNotifId(id: Int) {
        try {
            getSharedPreferences("shift_alarm_svc", Context.MODE_PRIVATE)
                .edit().putInt(OLD_NOTIF_KEY, id).apply()
        } catch (e: Exception) {
        }
    }

    /** 지난 알람의 알림 카드가 남아 있으면 지운다(새 알림으로 인식되게). */
    private fun cancelStaleNotifications() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(LEGACY_NOTIFICATION_ID)
            val saved = getSharedPreferences("shift_alarm_svc", Context.MODE_PRIVATE)
                .getInt(OLD_NOTIF_KEY, 0)
            if (saved != 0 && saved != notifId) nm.cancel(saved)
        } catch (e: Exception) {
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

    /**
     * 실제 출근 알람.
     *  - mode = "vibrate": 벨소리 없이 진동만 반복(해제할 때까지).
     *  - mode = "sound": 설정에서 고른 알람음 반복 + 진동 반복.
     *    v2.2 부터 음량(%) · 부스트(+6/+12dB) · 시작 방식(바로 최대/3초/8초)을 그대로 적용하고,
     *    설정이 켜져 있으면 울리는 동안만 폰의 알람 볼륨을 최대로 올린다.
     */
    private fun startRinging(a: ShiftAlarm) {
        if (!a.isVibrateOnly()) startSound()
        startVibration()
        // 소리·진동이 도는 동안 CPU 를 깨워 둔다(PARTIAL 은 화면을 켜지 않는다).
        holdPartialWake()
    }

    /** 설정한 알람음(없으면 시스템 기본 알람음)을 반복 재생한다. */
    private fun startSound() {
        // 폰 알람 볼륨을 최대로(설정 시) — 한 번만 올리고 끌 때 한 번 되돌린다
        AlarmAudio.raiseStreamVolume(this)
        // ① 고른 알람음을 MediaPlayer 로 — 음량·부스트·시작 방식을 모두 적용할 수 있다
        val mp = AlarmAudio.newPlayer(this)
        if (mp != null) {
            try {
                enhancer = AlarmAudio.boost(this, mp.audioSessionId)
                val level = AlarmAudio.playerLevel(this)
                val start = level * AlarmAudio.startLevel(this)
                mp.isLooping = true
                mp.setVolume(start, start)
                mp.start()
                player = mp
                if (AlarmAudio.fadeSeconds(this) > 0f) startFadeIn(start, level)
                return
            } catch (e: Exception) {
                try {
                    mp.release()
                } catch (e2: Exception) {
                }
            }
        }
        // ② 폴백: Ringtone (부스트는 못 걸지만 소리는 난다)
        try {
            releasePlayer()
            val fb = RingPrefs.alarmUri(this)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (fb != null) {
                val r = RingtoneManager.getRingtone(applicationContext, fb)
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
    }

    /** 점점 크게: 시작 방식에 따라 3초/8초 동안 [from] → [to] 로 0.25초 간격으로 올린다. */
    private fun startFadeIn(from: Float, to: Float) {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        val seconds = AlarmAudio.fadeSeconds(this)
        if (seconds <= 0f) return
        val steps = (seconds / 0.25f).toInt().coerceAtLeast(1)
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val f = from + (to - from) * (step.toFloat() / steps)
                try {
                    player?.setVolume(f, f)
                } catch (e: Exception) {
                }
                if (step < steps) handler.postDelayed(this, 250L)
            }
        }
        fadeRunnable = r
        handler.postDelayed(r, 250L)
    }

    private fun startVibration() {
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

    private fun releasePlayer() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        AlarmAudio.releaseBoost(enhancer)
        enhancer = null
    }

    /**
     * 테스트 알람: 벨소리 없이 4초짜리 진동만(반복 없음).
     * 진동이 끝나면 조용하고, 알람 화면은 1분 뒤 스스로 닫힌다.
     */
    private fun startTestBuzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = v
            // 0.35초 진동 + 0.25초 쉼 × 6 ≈ 3.6초
            val pattern = longArrayOf(0L, 350L, 250L, 350L, 250L, 350L, 250L, 350L, 250L, 350L, 250L, 350L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
        }
        // 진동이 도는 동안만 CPU 를 깨워 둔다.
        holdPartialWake(TEST_BUZZ_MS + 2_000L)
    }

    private fun holdPartialWake(ms: Long = 15 * 60 * 1000L) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (ringWake == null) {
                ringWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "posco:shift_alarm_ring")
            }
            ringWake?.acquire(ms)
        } catch (e: Exception) {
            ringWake = null
        }
    }

    /** 소리·진동·WakeLock 만 정리한다(알람 상태 current 는 그대로). */
    private fun releaseSound() {
        releasePlayer()
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
        // 울리는 동안 올렸던 폰 알람 볼륨을 원래대로 돌린다(v2.2)
        AlarmAudio.restoreStreamVolume(this)
        try {
            ringWake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        ringWake = null
    }

    private fun stopAlarm(reason: String) {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(retryRunnable)
        val had = current != null
        current = null
        activeTs = 0L
        releasePlayer()
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
        AlarmAudio.restoreStreamVolume(this)
        try {
            ringWake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        ringWake = null
        try {
            screenWake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        screenWake = null
        AlarmOverlay.hide()
        AlarmActivity.closeAll(this)
        try {
            NotificationManagerCompat.from(this).cancel(notifId)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(LEGACY_NOTIFICATION_ID)
            // 프로세스가 새로 뜬 경우(알림 액션만 눌렀을 때)에도 남은 알람 카드를 지운다
            val saved = getSharedPreferences("shift_alarm_svc", Context.MODE_PRIVATE)
                .getInt(OLD_NOTIF_KEY, 0)
            if (saved != 0 && saved != notifId) nm.cancel(saved)
        } catch (e: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
        }
        if (had) AlarmLog.end(this, reason)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
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
        releasePlayer()
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        AlarmAudio.restoreStreamVolume(this)
        try {
            ringWake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        try {
            screenWake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
