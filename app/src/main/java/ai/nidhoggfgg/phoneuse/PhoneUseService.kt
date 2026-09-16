package ai.nidhoggfgg.phoneuse

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import ai.nidhoggfgg.phoneuse.core.DeviceController
import ai.nidhoggfgg.phoneuse.device.AndroidBackend
import ai.nidhoggfgg.phoneuse.server.HttpServer
import ai.nidhoggfgg.phoneuse.server.IdentityStore

class PhoneUseService : Service() {
    lateinit var identities: IdentityStore
        private set
    lateinit var controller: DeviceController
        private set
    private var server: HttpServer? = null
    private var starting = false
    private val settings by lazy { getSharedPreferences("service_state", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastClient: String? = null
    @Volatile private var lastContact = 0L
    @Volatile private var destroyed = false
    private var notificationText = ""
    private val refresh = object : Runnable {
        override fun run() { updateNotification(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone Use 控制", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification("服务正在启动"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification("服务正在启动"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> setPaused(true)
            RESUME -> setPaused(false)
            STOP -> stopEndpoint()
            else -> {
                settings.edit().putBoolean("enabled", true).apply()
                if (server == null && !starting) startEndpoint()
            }
        }
        return if (settings.getBoolean("enabled", false)) START_STICKY else START_NOT_STICKY
    }
    fun setPaused(paused: Boolean) {
        settings.edit().putBoolean("paused", paused).apply()
        if (::controller.isInitialized) {
            if (paused) controller.pause() else controller.resume()
        }
    }
    fun stopEndpoint() {
        settings.edit().putBoolean("enabled", false).apply()
        stopSelf()
    }
    private fun startEndpoint() {
        starting = true
        Thread({
            try {
                val identity = IdentityStore(this)
                handler.post {
                    if (destroyed) return@post
                    identities = identity
                    controller = DeviceController(AndroidBackend(this), SystemClock::elapsedRealtime, identities::exists) {
                        handler.post { if (!destroyed) updateNotification() }
                    }
                    if (settings.getBoolean("paused", false)) controller.pause()
                    try {
                        server = HttpServer(identities, controller, assets) { client ->
                            lastClient = client; lastContact = SystemClock.elapsedRealtime()
                            handler.post { if (!destroyed) updateNotification() }
                        }.also { it.start() }
                        current = this
                        lastError = null
                        handler.post(refresh)
                    } catch (e: Exception) { lastError = "HTTP 启动失败：${e.javaClass.simpleName} ${e.message.orEmpty()}"; stopSelf() }
                    starting = false
                }
            } catch (e: Exception) {
                lastError = "客户端存储初始化失败：${e.javaClass.simpleName}"
                handler.post { stopSelf() }
            }
        }, "phone-use-start").start()
    }
    fun revokeClient(id: String) {
        identities.revoke(id)
        identities.pairing.revoked(id)
        controller.revokeClient(id)
        if (lastClient == id) lastClient = null
        updateNotification()
    }
    fun stateText(): String {
        if (!::controller.isInitialized) return "服务正在启动"
        return when {
            identities.pairing.pending().isNotEmpty() -> "收到配对请求 · 点击打开 App 授权"
            controller.gate.paused -> "控制已暂停"
            lastClient != null && SystemClock.elapsedRealtime() - lastContact < 60_000 -> "客户端最近有请求 · 服务可用"
            else -> "服务可用，等待连接"
        }
    }
    private fun updateNotification() {
        val text = stateText()
        if (notificationText == text) return
        notificationText = text
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, PhoneUseService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = ::controller.isInitialized && controller.gate.paused
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_control)
            .setContentTitle("Phone Use").setContentText(text).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, if (paused) "恢复" else "暂停", action(if (paused) RESUME else PAUSE, 1))
            .addAction(0, "停止服务", action(STOP, 3)).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        if (current === this) current = null
        if (::controller.isInitialized) controller.stop()
        if (::identities.isInitialized) identities.pairing.clear()
        server?.stop(); server = null
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "phone_use_control"
        const val PAUSE = "phoneuse.PAUSE"
        const val RESUME = "phoneuse.RESUME"
        const val STOP = "phoneuse.STOP"
        @Volatile var current: PhoneUseService? = null
            private set
        @Volatile var lastError: String? = null
            private set
    }
}
