package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.applyPromotedProgress
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.HttpServer
import io.legado.app.web.WebSocketServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager
import splitties.systemservices.notificationManager
import java.io.IOException

class WebService : BaseService() {

    companion object {
        private const val TERMINAL_NOTIFICATION_DURATION = 4_500L

        var isRun = false
        var hostAddress = ""

        fun start(context: Context) {
            context.startService<WebService>()
        }

        fun startForeground(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            if (isRun) {
                context.startService<WebService> {
                    action = IntentAction.stop
                }
            } else {
                context.stopService<WebService>()
            }
        }

        fun serve() {
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    @Volatile
    private var stopping = false
    private var terminalStopJob: Job? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        stopping = false
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        upTile(true)
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            if (!stopping) {
                val addressList = NetworkUtils.getLocalIPAddress()
                notificationList.clear()
                if (addressList.any()) {
                    notificationList.addAll(addressList.map { address ->
                        getString(
                            R.string.http_ip,
                            address.hostAddress,
                            getPort()
                        )
                    })
                    hostAddress = notificationList.first()
                } else {
                    hostAddress = getString(R.string.network_connection_unavailable)
                    notificationList.add(hostAddress)
                }
                startForegroundNotification()
                postEvent(EventBus.WEB_SERVICE, hostAddress)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopServiceWithNotification()
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> if (useWakeLock) {
                wakeLock.acquire()
                wifiLock?.acquire()
            }

            else -> {
                terminalStopJob?.cancel()
                terminalStopJob = null
                stopping = false
                upWebServer()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        terminalStopJob?.cancel()
        terminalStopJob = null
        stopping = true
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun = false
        stopServers()
        hostAddress = ""
        postEvent(EventBus.WEB_SERVICE, "")
        upTile(false)
    }

    private fun stopServiceWithNotification() {
        if (stopping) return
        stopping = true
        stopServers()
        isRun = false
        hostAddress = ""
        postEvent(EventBus.WEB_SERVICE, "")
        upTile(false)
        val (builder, promoted) = createNotification(terminal = true)
        if (!promoted) {
            stopSelf()
            return
        }
        startForeground(NotificationId.WebService, builder.build())
        terminalStopJob?.cancel()
        terminalStopJob = lifecycleScope.launch {
            delay(TERMINAL_NOTIFICATION_DURATION)
            notificationManager.cancel(NotificationId.WebService)
            stopSelf()
        }
    }

    private fun stopServers() {
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        httpServer = null
        webSocketServer = null
    }

    private fun upWebServer() {
        if (stopping) return
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        val addressList = NetworkUtils.getLocalIPAddress()
        if (addressList.any()) {
            val port = getPort()
            httpServer = HttpServer(port)
            webSocketServer = WebSocketServer(port + 1)
            try {
                httpServer?.start()
                webSocketServer?.start(1000 * 30) // 通信超时设置
                notificationList.clear()
                notificationList.addAll(addressList.map { address ->
                    getString(
                        R.string.http_ip,
                        address.hostAddress,
                        getPort()
                    )
                })
                hostAddress = notificationList.first()
                isRun = true
                postEvent(EventBus.WEB_SERVICE, hostAddress)
                startForegroundNotification()
            } catch (e: IOException) {
                toastOnUi(e.localizedMessage ?: "")
                e.printOnDebug()
                stopSelf()
            }
        } else {
            toastOnUi("web service cant start, no ip address")
            stopSelf()
        }
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.webPort, 1122)
        if (port !in 1024..65530) {
            port = 1122
        }
        return port
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val (builder, _) = createNotification(terminal = stopping)
        startForeground(NotificationId.WebService, builder.build())
    }

    private fun createNotification(terminal: Boolean = false): Pair<NotificationCompat.Builder, Boolean> {
        val statusText = getString(
            if (terminal) R.string.web_service_live_stopped else R.string.web_service_live_started
        )
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(!terminal)
            .setContentTitle(statusText)
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        val promoted = builder.applyPromotedProgress(
            this,
            AppConst.channelIdWeb,
            eligible = terminal || httpServer?.isAlive == true,
            ongoing = true,
            max = 0,
            progress = 0,
            criticalText = statusText,
            terminal = terminal
        )
        if (!promoted) {
            builder.setContentTitle(getString(R.string.web_service))
        }
        if (!terminal) {
            builder.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<WebService>(IntentAction.stop)
            )
        } else if (promoted) {
            builder.setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)
        }
        return builder to promoted
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}
