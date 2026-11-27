package com.matheus.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** "Mãos" do bot: toques, arrastos e gesto de 2 dedos. */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): GestureDescription.StrokeDescription {
        val p = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        return GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))
    }

    private suspend fun run(vararg strokes: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { c ->
            val b = GestureDescription.Builder()
            strokes.forEach { b.addStroke(it) }
            val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (c.isActive) c.resume(true) }
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
        return suspendCancellableCoroutine { c ->
            val b = GestureDescription.Builder()
            for (i in 0 until n) {
                b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, i * period, durMs.coerceAtLeast(1)))
            }
            val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }
    }
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) =
        run(stroke(x1, y1, x2, y2, dur))

    /**
     * Arrastar um item: segura parado `holdMs`, move por `moveMs` e FICA PARADO no destino por `endHoldMs` antes de soltar.
     * Sem o "segurar" inicial, o jogo trata o gesto como rolagem da lista. Sem a parada no fim, o dedo solta na chegada e a carta,
     * que o jogo anima um pouco atrás do dedo, pode cair no meio do caminho em vez de dentro do quadrado.
     * Usa gestos encadeados (continueStroke): o dedo fica na tela entre eles.
     */
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, moveMs: Long, endHoldMs: Long = 400L): Boolean {
        val hold = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1) }, 0, holdMs.coerceAtLeast(1), true)
        if (!run(hold)) return false
        val move = hold.continueStroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, moveMs.coerceAtLeast(1), true)
        if (!run(move)) return false
        return run(move.continueStroke(Path().apply { moveTo(x2, y2) }, 0, endHoldMs.coerceAtLeast(1), false))
    }

    /**
     * Arrastar passando por um ponto intermediário: segura em (x1,y1), vai até (xm,ym) (ex.: para CIMA, saindo da faixa da lista, para o
     * jogo não ler como rolagem horizontal) e depois até (x2,y2), onde fica parado `endHoldMs` antes de soltar.
     */
    suspend fun dragVia(
        x1: Float, y1: Float, xm: Float, ym: Float, x2: Float, y2: Float,
        holdMs: Long, move1Ms: Long, move2Ms: Long, endHoldMs: Long = 400L,
    ): Boolean {
        val hold = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1) }, 0, holdMs.coerceAtLeast(1), true)
        if (!run(hold)) return false
        val m1 = hold.continueStroke(Path().apply { moveTo(x1, y1); lineTo(xm, ym) }, 0, move1Ms.coerceAtLeast(1), true)
        if (!run(m1)) return false
        val m2 = m1.continueStroke(Path().apply { moveTo(xm, ym); lineTo(x2, y2) }, 0, move2Ms.coerceAtLeast(1), true)
        if (!run(m2)) return false
        return run(m2.continueStroke(Path().apply { moveTo(x2, y2) }, 0, endHoldMs.coerceAtLeast(1), false))
    }

    /**
     * Rolagem SEM "lançar": arrasta `moveMs` e fica parado no fim `holdMs` antes de soltar. Como o dedo solta
     * parado, a lista não ganha inércia e anda exatamente a distância pedida (não pula cartas).
     */
    suspend fun scrollStop(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long, holdMs: Long): Boolean {
        val first = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, moveMs.coerceAtLeast(1), true)
        if (!run(first)) return false
        return run(first.continueStroke(Path().apply { moveTo(x2, y2) }, 0, holdMs.coerceAtLeast(1), false))
    }

    /** Bloqueio: dois dedos ao mesmo tempo. */
    suspend fun block(w: Int, h: Int, dur: Long) = run(
        stroke(w * 0.60f, h * 0.45f, w * 0.60f, h * 0.45f, dur),
        stroke(w * 0.72f, h * 0.55f, w * 0.72f, h * 0.55f, dur),
    )
}
