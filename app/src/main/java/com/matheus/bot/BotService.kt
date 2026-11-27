package com.matheus.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || projection != null) return START_NOT_STICKY

        val ch = "bot"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("Bot Sobrevivente ativo")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        // Android 14+: tem que entrar em primeiro plano ANTES de pegar o MediaProjection.
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
            ?: return START_NOT_STICKY
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } }
        }, main)
        projection = mp

        OpenCVLoader.initLocal()
        wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        // O jogo é horizontal: captura sempre em paisagem (ex.: 2400x1080).
        val w = maxOf(b.width(), b.height())
        val h = minOf(b.width(), b.height())
        val cap = Capture(mp, w, h, resources.displayMetrics.densityDpi)
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
        val toggle = miniButton(this, "▶ Play") {
            val b = brain ?: return@miniButton
            if (b.isPaused) b.start() else b.pause()
        }
        brain?.onPausedChange = { p -> main.post { toggle.text = if (p) "▶ Play" else "⏸ Pausa" } }
        box.addView(toggle)
        box.addView(miniButton(this, "■ Sair") { shutdown() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(box, lp)
        panel = box
    }

    private fun shutdown() {
        brain?.stop(); brain = null
        if (::wm.isInitialized) panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        capture?.release(); capture = null
        val p = projection
        projection = null
        p?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
