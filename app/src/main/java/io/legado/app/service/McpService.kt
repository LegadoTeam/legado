package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.applyPromotedProgress
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.mcp.McpAccess
import io.legado.app.web.mcp.McpToolServer
import io.legado.app.web.mcp.configureMcp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager

class McpService : BaseService() {

    companion object {
        private const val TERMINAL_NOTIFICATION_DURATION = 4_500L

        @Volatile
        var isRun = false

        @Volatile
        var hostAddress = ""

        private const val ACTION_RESTART = "restartMcpService"

        fun start(context: Context) {
            context.startService<McpService>()
        }

        fun restart(context: Context) {
            context.startService<McpService> { action = ACTION_RESTART }
        }

        fun stop(context: Context) {
            if (isRun) {
                context.startService<McpService> {
                    action = IntentAction.stop
                }
            } else {
                context.stopService<McpService>()
            }
        }
    }

    private var engine: EmbeddedServer<*, *>? = null
    private var activeAddressKeys: List<String> = emptyList()
    @Volatile
    private var destroyed = false
    @Volatile
    private var stopping = false
    private var terminalStopJob: Job? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private val networkChangedListener by lazy {
        NetworkChangedListener(this, includeDetailedChanges = true)
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        stopping = false
        networkChangedListener.onNetworkChanged = {
            synchronized(this) {
                if (!destroyed && !stopping) {
                    val addresses = NetworkUtils.getLocalIPAddress()
                    if (isRun) {
                        val addressKeys = addresses.mapNotNull { it.hostAddress }.sorted()
                        if (addressKeys != activeAddressKeys) {
                            upMcpServer()
                        }
                    } else {
                        updateAddresses(addresses)
                    }
                }
            }
        }
        networkChangedListener.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopServiceWithNotification()
            "copyHostAddress" -> sendToClip(hostAddress)
            ACTION_RESTART -> {
                terminalStopJob?.cancel()
                terminalStopJob = null
                stopping = false
                upMcpServer()
            }
            else -> {
                terminalStopJob?.cancel()
                terminalStopJob = null
                stopping = false
                upMcpServer()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @Synchronized
    override fun onDestroy() {
        terminalStopJob?.cancel()
        terminalStopJob = null
        destroyed = true
        stopping = true
        isRun = false
        networkChangedListener.unRegister()
        stopEngine()
        hostAddress = ""
        activeAddressKeys = emptyList()
        postEvent(EventBus.MCP_SERVICE, "")
        super.onDestroy()
    }

    @Synchronized
    private fun stopServiceWithNotification() {
        if (stopping) return
        stopping = true
        isRun = false
        stopEngine()
        hostAddress = ""
        activeAddressKeys = emptyList()
        postEvent(EventBus.MCP_SERVICE, "")
        val (builder, promoted) = createNotification(terminal = true)
        if (!promoted) {
            stopSelf()
            return
        }
        startForeground(NotificationId.McpService, builder.build())
        terminalStopJob?.cancel()
        terminalStopJob = lifecycleScope.launch {
            delay(TERMINAL_NOTIFICATION_DURATION)
            notificationManager.cancel(NotificationId.McpService)
            stopSelf()
        }
    }

    @Synchronized
    private fun upMcpServer() {
        if (destroyed) return
        if (stopping) return
        val token = AppConfig.jsSourceApiToken
        if (AppConfig.jsSourceApiTokenRequired && token.isNullOrBlank()) {
            stopWithError(getString(R.string.mcp_service_token_required))
            return
        }

        stopEngine()
        val addresses = NetworkUtils.getLocalIPAddress()
        val port = getPort()
        val allowedHosts = McpAccess.allowedHosts(addresses)
        val allowedOrigins = McpAccess.allowedOrigins(allowedHosts)
        try {
            val nextEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                configureMcp(
                    tokenRequiredProvider = { AppConfig.jsSourceApiTokenRequired },
                    tokenProvider = { AppConfig.jsSourceApiToken },
                    unauthorizedMessage = {
                        this@McpService.getString(R.string.mcp_service_token_invalid)
                    },
                    allowedHosts = allowedHosts,
                    allowedOrigins = allowedOrigins,
                ) {
                    McpToolServer.create()
                }
            }
            nextEngine.start(wait = false)
            engine = nextEngine
            isRun = true
            activeAddressKeys = addresses.mapNotNull { it.hostAddress }.sorted()
            updateAddresses(addresses, port)
        } catch (error: Exception) {
            error.printOnDebug()
            stopWithError(error.localizedMessage ?: getString(R.string.mcp_service_start_failed))
        }
    }

    private fun stopEngine() {
        engine?.stop(500, 1_000)
        engine = null
    }

    private fun stopWithError(message: String) {
        isRun = false
        appCtx.putPrefBoolean(PreferKey.mcpService, false)
        toastOnUi(message)
        stopSelf()
    }

    private fun updateAddresses(
        addresses: List<java.net.InetAddress> = NetworkUtils.getLocalIPAddress(),
        port: Int = getPort(),
    ) {
        if (stopping) return
        notificationList = McpAccess.endpointUrls(addresses, port).toMutableList()
        hostAddress = notificationList.first()
        startForegroundNotification()
        postEvent(EventBus.MCP_SERVICE, hostAddress)
    }

    private fun getPort(): Int {
        return AppConfig.mcpPort.takeIf { it in 1024..65530 } ?: 1236
    }

    override fun startForegroundNotification() {
        val (builder, _) = createNotification(terminal = stopping)
        startForeground(NotificationId.McpService, builder.build())
    }

    private fun createNotification(terminal: Boolean = false): Pair<NotificationCompat.Builder, Boolean> {
        val statusText = getString(
            if (terminal) R.string.mcp_service_live_stopped else R.string.mcp_service_live_started
        )
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(!terminal)
            .setContentTitle(statusText)
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(servicePendingIntent<McpService>("copyHostAddress"))
        val promoted = builder.applyPromotedProgress(
            this,
            AppConst.channelIdWeb,
            eligible = terminal || isRun,
            ongoing = true,
            max = 0,
            progress = 0,
            criticalText = statusText,
            terminal = terminal
        )
        if (!promoted) {
            builder.setContentTitle(getString(R.string.mcp_service))
        }
        if (!terminal) {
            builder.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<McpService>(IntentAction.stop),
            )
        } else if (promoted) {
            builder.setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)
        }
        return builder to promoted
    }
}
