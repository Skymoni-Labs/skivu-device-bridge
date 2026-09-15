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
import ai.nidhoggfgg.phoneuse.server.HttpsServer
import ai.nidhoggfgg.phoneuse.server.IdentityStore

class PhoneUseService : Service() {
    lateinit var identities: IdentityStore
        private set
    lateinit var controller: DeviceController
        private set
    private var server: HttpsServer? = null
    private var starting = false
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
            PAUSE -> if (::controller.isInitialized) controller.pause()
            RESUME -> if (::controller.isInitialized) controller.resume()
            DISCONNECT -> if (::controller.isInitialized) { controller.revoke(); lastClient = null; updateNotification() }
            STOP -> stopSelf()
            else -> if (server == null && !starting) startEndpoint()
        }
        return START_NOT_STICKY
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
                    try {
                        server = HttpsServer(identities, controller) { client ->
                            lastClient = client; lastContact = SystemClock.elapsedRealtime()
                            handler.post { if (!destroyed) updateNotification() }
                        }.also { it.start() }
                        current = this
                        lastError = null
                        handler.post(refresh)
                    } catch (e: Exception) { lastError = "HTTPS 启动失败：${e.javaClass.simpleName} ${e.message.orEmpty()}"; stopSelf() }
                    starting = false
                }
            } catch (e: Exception) {
                lastError = "证书初始化失败：${e.javaClass.simpleName}"
                handler.post { stopSelf() }
            }
        }, "phone-use-start").start()
    }
    fun revokeClient(id: String) {
        identities.revoke(id)
        if (controller.gate.current()?.client == id) controller.revoke()
        if (lastClient == id) lastClient = null
        updateNotification()
    }
    fun stateText(): String {
        if (!::controller.isInitialized) return "服务正在启动"
        val lease = controller.gate.current()
        return when {
            controller.gate.paused -> "控制已暂停"
            lease != null -> "手机正在被控制 · ${identities.clients().find { it.first == lease.client }?.second ?: "客户端"}"
            lastClient != null && SystemClock.elapsedRealtime() - lastContact < 60_000 -> "客户端最近已连接，等待控制"
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
            .addAction(0, "断开", action(DISCONNECT, 2)).addAction(0, "停止服务", action(STOP, 3)).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        if (current === this) current = null
        if (::controller.isInitialized) controller.stop()
        if (::identities.isInitialized) identities.closePairing()
        server?.stop(); server = null
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "phone_use_control"
        const val PAUSE = "phoneuse.PAUSE"
        const val RESUME = "phoneuse.RESUME"
        const val DISCONNECT = "phoneuse.DISCONNECT"
        const val STOP = "phoneuse.STOP"
        @Volatile var current: PhoneUseService? = null
            private set
        @Volatile var lastError: String? = null
            private set
    }
}
