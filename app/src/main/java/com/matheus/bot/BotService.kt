package com.matheus.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.opencv.android.OpenCVLoader
import java.io.File

/** Serviço em primeiro plano: captura de tela + painel flutuante + cérebro. */
class BotService : Service() {
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var projection: MediaProjection? = null
    private var capture: Capture? = null
    private var brain: Brain? = null
    private var panel: View? = null
    private var statusView: TextView? = null

    companion object {
        const val ACTION_TOGGLE = "com.matheus.bot.TOGGLE_PAUSE"
        private const val CHANNEL = "bot"
        private const val NOTIF_ID = 1

        /** Serviço em execução (null se a captura não foi iniciada). A tela principal usa para o botão de pausa. */
        @Volatile var instance: BotService? = null

        /** A tela principal escuta as mudanças de pausa (o bot também pausa sozinho). Sempre chamado na thread principal. */
        @Volatile var pauseListener: ((Boolean) -> Unit)? = null
    }

    /** true = pausado, false = rodando, null = bot ainda não iniciado. */
    val paused: Boolean? get() = brain?.isPaused

    /** Pausa se estiver rodando; continua se estiver pausado. Usado pela notificação, pelo painel e pela tela principal. */
    fun toggle() {
        val b = brain ?: return
        if (b.isPaused) b.start() else b.pause()
    }

    /** Notificação fixa com o botão Pausar/Continuar (aparece ao expandir a notificação). */
    private fun buildNotification(paused: Boolean): Notification {
        val togglePi = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(
            this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
        )
        val action = Notification.Action.Builder(icon, if (paused) "▶ Continuar" else "⏸ Pausar", togglePi).build()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (paused) "Bot Sobrevivente pausado" else "Bot Sobrevivente rodando")
            .setContentText(if (paused) "Toque em Continuar para voltar" else "Toque em Pausar para parar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(paused: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(paused))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Botão da notificação. Se o serviço não estiver com o bot montado, não fica vivo à toa.
        if (intent?.action == ACTION_TOGGLE) {
            if (brain != null) toggle() else stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || projection != null) return START_NOT_STICKY

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Bot", NotificationManager.IMPORTANCE_LOW))
        // O bot começa pausado (▶ Play), então a notificação começa com o botão Continuar.
        val n = buildNotification(true)
        // Android 14+: tem que entrar em primeiro plano ANTES de pegar o MediaProjection.
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
            ?: return START_NOT_STICKY
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } }
        }, main)
        projection = mp

        OpenCVLoader.initLocal()
        CardArt.init(this)   // referências de arte das cartas (assets/cartas); se falhar, o bot segue só com o OCR
        wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        // O jogo é horizontal: captura sempre em paisagem (ex.: 2400x1080).
        val w = maxOf(b.width(), b.height())
        val h = minOf(b.width(), b.height())
        // Captura em % da tela (padrão 100). Os gestos continuam na tela real: BotAccessibilityService.gestureScale converte.
        val pct = BotConfig(this).captureScalePct.coerceIn(30, 100)
        val capW = ((w * pct / 100) / 2 * 2).coerceAtLeast(2)
        val capH = ((h * pct / 100) / 2 * 2).coerceAtLeast(2)
        BotAccessibilityService.gestureScale = w.toFloat() / capW
        val cap = Capture(mp, capW, capH, resources.displayMetrics.densityDpi)
        capture = cap

        val base = getExternalFilesDir(null)
        val dbgDir = File(base, "debug").apply { mkdirs() }
        brain = Brain(scope, cap, TextVision(), BotConfig(this), dbgDir) { msg ->
            main.post { statusView?.text = msg }
        }
        // O painel aparece na captura e cobre o quadrado 1 do time; durante a montagem de times o Brain o esconde.
        brain?.showUi = { vis -> main.post { panel?.visibility = if (vis) View.VISIBLE else View.INVISIBLE } }
        showPanel()
        return START_NOT_STICKY
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(8, 8, 8, 8)
        }
        val st = TextView(this).apply {
            text = "Parado"; setTextColor(Color.WHITE); textSize = 10f; maxWidth = 360
        }
        statusView = st
        box.addView(st)
        // Um botão só: começa pausado (▶ Play); apertar roda; apertar de novo pausa; e assim por diante.
        // O texto acompanha o estado real do Brain (ele também pausa sozinho, ex.: time morto).
        val playBtn = miniButton(this, "▶ Play") { toggle() }
        // O texto do painel, a notificação e o botão da tela principal acompanham o estado real do Brain
        // (ele também pausa sozinho, ex.: time morto).
        brain?.onPausedChange = { p ->
            main.post {
                playBtn.text = if (p) "▶ Play" else "⏸ Pausa"
                updateNotification(p)
                pauseListener?.invoke(p)
            }
        }
        box.addView(playBtn)
        box.addView(miniButton(this, "■ Sair") { shutdown() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // KEEP_SCREEN_ON: o bot roda por horas sem ninguém tocando; com a tela apagando, a captura para.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(box, lp)
        panel = box
    }

    private fun shutdown() {
        brain?.stop(); brain = null
        if (::wm.isInitialized) panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        BotAccessibilityService.gestureScale = 1f
        capture?.release(); capture = null
        val p = projection
        projection = null
        p?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}
