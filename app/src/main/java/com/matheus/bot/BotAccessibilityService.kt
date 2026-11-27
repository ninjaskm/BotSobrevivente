package com.matheus.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Um arrasto curto de um lote (ver [BotAccessibilityService.swipeBatch]). */
class SwipeSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dur: Long)

/** "Mãos" do bot: toques, arrastos e gesto de 2 dedos. */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotAccessibilityService? = null

        /**
         * Todos os gestos recebem coordenadas no espaço da CAPTURA. Se a captura for menor que a tela real
         * (BotConfig.captureScalePct < 100), o BotService grava aqui a relação tela real / captura e cada coordenada é multiplicada por ela.
         */
        @Volatile var gestureScale = 1f

        /** Horário (ms) do último gesto enviado (toque, rajada, arrasto, lote, bloqueio). O vigia de inatividade do Brain usa isto. */
        @Volatile var lastGestureAt = 0L
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Caminho em coordenadas da tela real (aplica [gestureScale]). Sem linha se o ponto não muda. */
    private fun pth(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val k = gestureScale
        return Path().apply {
            moveTo(x1 * k, y1 * k)
            if (x1 != x2 || y1 != y2) lineTo(x2 * k, y2 * k)
        }
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): GestureDescription.StrokeDescription =
        GestureDescription.StrokeDescription(pth(x1, y1, x2, y2), 0, dur.coerceAtLeast(1))

    private suspend fun run(vararg strokes: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { c ->
            lastGestureAt = System.currentTimeMillis()
            val b = GestureDescription.Builder()
            strokes.forEach { b.addStroke(it) }
            val t0 = System.nanoTime()
            val dur = strokes.maxOf { it.duration }   // ms do próprio gesto: o que passar disso é atraso do Android
            val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    val ovUs = (System.nanoTime() - t0) / 1000 - dur * 1000
                    Perf.addUs("gesture_overhead", ovUs)
                    // Só toques e arrastos curtos entram na mediana usada na autocalibração do ping pong.
                    if (strokes.size <= 2 && dur <= 100) Perf.overheadMs.add((ovUs / 1000).coerceAtLeast(0L))
                    if (c.isActive) c.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }

    suspend fun tap(x: Float, y: Float, dur: Long = 40L) = run(stroke(x, y, x, y, dur))

    /**
     * Rajada de toques no mesmo ponto em UM só gesto (até 10 toques, um a cada `periodMs`, sem se sobrepor).
     * Bem mais rápido que chamar tap() em sequência, porque cada dispatchGesture tem um custo fixo de ida e volta.
     */
    suspend fun tapBurst(x: Float, y: Float, count: Int = 6, periodMs: Long = 25L, durMs: Long = 8L): Boolean {
        val n = count.coerceIn(1, 10)
        val period = periodMs.coerceAtLeast(durMs + 2)
        val totalMs = (n - 1) * period + durMs.coerceAtLeast(1)
        return suspendCancellableCoroutine { c ->
            lastGestureAt = System.currentTimeMillis()
            val b = GestureDescription.Builder()
            val t0 = System.nanoTime()
            for (i in 0 until n) {
                b.addStroke(GestureDescription.StrokeDescription(pth(x, y, x, y), i * period, durMs.coerceAtLeast(1)))
            }
            val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    Perf.addUs("gesture_overhead_burst", (System.nanoTime() - t0) / 1000 - totalMs * 1000)
                    if (c.isActive) c.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) =
        run(stroke(x1, y1, x2, y2, dur))

    /**
     * Vários arrastos curtos em UM só gesto, um depois do outro (sem se sobrepor), com `gapMs` entre eles (mínimo 2 ms).
     * Como a [tapBurst], evita o custo fixo de ida e volta de cada dispatchGesture. Use lotes curtos (2 a 4): enquanto o lote
     * roda o bot não consegue parar o combo. Respeita GestureDescription.getMaxStrokeCount().
     */
    suspend fun swipeBatch(segs: List<SwipeSeg>, gapMs: Long = 2L): Boolean {
        if (segs.isEmpty()) return true
        val list = segs.take(GestureDescription.getMaxStrokeCount().coerceAtLeast(1))
        val gap = gapMs.coerceAtLeast(2L)
        var totalMs = 0L
        for (s in list) totalMs += s.dur.coerceAtLeast(1) + gap
        totalMs -= gap
        return suspendCancellableCoroutine { c ->
            lastGestureAt = System.currentTimeMillis()
            val b = GestureDescription.Builder()
            val t0 = System.nanoTime()
            var start = 0L
            for (s in list) {
                val d = s.dur.coerceAtLeast(1)
                b.addStroke(GestureDescription.StrokeDescription(pth(s.x1, s.y1, s.x2, s.y2), start, d))
                start += d + gap
            }
            val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    Perf.addUs("gesture_overhead_batch", (System.nanoTime() - t0) / 1000 - totalMs * 1000)
                    if (c.isActive) c.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }
    }

    /**
     * Arrastar um item: segura parado `holdMs`, move por `moveMs` e FICA PARADO no destino por `endHoldMs` antes de soltar.
     * Sem o "segurar" inicial, o jogo trata o gesto como rolagem da lista. Sem a parada no fim, o dedo solta na chegada e a carta,
     * que o jogo anima um pouco atrás do dedo, pode cair no meio do caminho em vez de dentro do quadrado.
     * Usa gestos encadeados (continueStroke): o dedo fica na tela entre eles.
     */
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, moveMs: Long, endHoldMs: Long = 400L): Boolean {
        val hold = GestureDescription.StrokeDescription(pth(x1, y1, x1, y1), 0, holdMs.coerceAtLeast(1), true)
        if (!run(hold)) return false
        val move = hold.continueStroke(pth(x1, y1, x2, y2), 0, moveMs.coerceAtLeast(1), true)
        if (!run(move)) return false
        return run(move.continueStroke(pth(x2, y2, x2, y2), 0, endHoldMs.coerceAtLeast(1), false))
    }

    /**
     * Arrastar passando por um ponto intermediário: segura em (x1,y1), vai até (xm,ym) (ex.: para CIMA, saindo da faixa da lista, para o
     * jogo não ler como rolagem horizontal) e depois até (x2,y2), onde fica parado `endHoldMs` antes de soltar.
     */
    suspend fun dragVia(
        x1: Float, y1: Float, xm: Float, ym: Float, x2: Float, y2: Float,
        holdMs: Long, move1Ms: Long, move2Ms: Long, endHoldMs: Long = 400L,
    ): Boolean {
        val hold = GestureDescription.StrokeDescription(pth(x1, y1, x1, y1), 0, holdMs.coerceAtLeast(1), true)
        if (!run(hold)) return false
        val m1 = hold.continueStroke(pth(x1, y1, xm, ym), 0, move1Ms.coerceAtLeast(1), true)
        if (!run(m1)) return false
        val m2 = m1.continueStroke(pth(xm, ym, x2, y2), 0, move2Ms.coerceAtLeast(1), true)
        if (!run(m2)) return false
        return run(m2.continueStroke(pth(x2, y2, x2, y2), 0, endHoldMs.coerceAtLeast(1), false))
    }

    /**
     * Rolagem SEM "lançar": arrasta `moveMs` e fica parado no fim `holdMs` antes de soltar. Como o dedo solta
     * parado, a lista não ganha inércia e anda exatamente a distância pedida (não pula cartas).
     */
    suspend fun scrollStop(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long, holdMs: Long): Boolean {
        val first = GestureDescription.StrokeDescription(pth(x1, y1, x2, y2), 0, moveMs.coerceAtLeast(1), true)
        if (!run(first)) return false
        return run(first.continueStroke(pth(x2, y2, x2, y2), 0, holdMs.coerceAtLeast(1), false))
    }

    /** Bloqueio: dois dedos ao mesmo tempo. */
    suspend fun block(w: Int, h: Int, dur: Long) = run(
        stroke(w * 0.60f, h * 0.45f, w * 0.60f, h * 0.45f, dur),
        stroke(w * 0.72f, h * 0.55f, w * 0.72f, h * 0.55f, dur),
    )
}
