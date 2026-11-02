package com.unfreezeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper

/**
 * 守护服务：前台常驻 + HandlerThread 定时巡检。
 * 巡检逻辑全部在 GuardEngine，本类只负责调度、通知与豁免补刷。
 */
class GuardService : Service() {

    companion object {
        const val ACTION_START = "com.unfreezeguard.action.START"
        const val ACTION_STOP = "com.unfreezeguard.action.STOP"
        const val CHANNEL_ID = "guard"
        const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false

        /** UI/日志观察者：GuardService → MainActivity 实时状态 */
        @Volatile
        var statusListener: ((String) -> Unit)? = null
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var loops = 0L
    private var lastStatus = ""

    private val tick = object : Runnable {
        override fun run() {
            val pkgs = PkgStore.cached
            loops++
            if (pkgs.isEmpty()) {
                updateStatus("守护运行中（未选择应用）")
            } else {
                if (loops % GuardEngine.EXEMPT_EVERY_LOOPS == 1L) {
                    GuardEngine.refreshExemptions(pkgs)
                }
                val actions = GuardEngine.sweep(pkgs)
                val summary = if (actions.isEmpty()) {
                    "守护运行中：${pkgs.size} 个应用，状态正常"
                } else {
                    "守护运行中：" + actions.joinToString("；")
                }
                updateStatus(summary)
            }
            handler?.postDelayed(this, GuardEngine.LOOP_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("守护启动中…"))
        isRunning = true
        PkgStore.cached = PkgStore.loadSync(this)
        if (handler == null) {
            thread = HandlerThread("UnfreezeGuard").apply { start() }
            handler = Handler(thread!!.looper)
            handler?.post(tick)
        }
        updateStatus("守护运行中：${PkgStore.cached.size} 个应用")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        statusListener?.invoke("已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        statusListener?.invoke(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "防冻结守护", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("防冻结守护")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止守护", stop).build())
            .build()
    }
}
