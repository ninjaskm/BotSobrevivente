package com.matheus.bot

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Quadro reduzido (largura 800) com análises que não precisam de template. */
class Snap(val w: Int, val h: Int, private val hsv: Mat, private val gray: Mat) {

    private fun r(l: Float, t: Float, rr: Float, b: Float): Rect {
        val x0 = (l * w).toInt().coerceIn(0, w - 1)
        val y0 = (t * h).toInt().coerceIn(0, h - 1)
        val x1 = (rr * w).toInt().coerceIn(x0 + 1, w)
        val y1 = (b * h).toInt().coerceIn(y0 + 1, h)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun frac(rect: Rect, lo: Scalar, hi: Scalar): Double {
        val sub = Mat(hsv, rect)
        val m = Mat()
        Core.inRange(sub, lo, hi, m)
        val v = Core.mean(m).`val`[0] / 255.0
        sub.release(); m.release()
        return v
    }

    /** Barras de vida azuis no topo (só existem no combate). */
    fun hud(): Double = frac(r(0.30f, 0.025f, 0.80f, 0.11f), Scalar(95.0, 12.0, 170.0), Scalar(125.0, 170.0, 255.0))   // saturação mínima baixa: a barra fica quase branca quando brilha

    /** Slots roxos do medidor de poder, embaixo à esquerda (só existem no combate). */
    fun purple(): Double = frac(r(0.06f, 0.88f, 0.40f, 0.96f), Scalar(120.0, 50.0, 40.0), Scalar(155.0, 255.0, 230.0))

    /**
     * Combate = slots roxos embaixo + um pouco de azul da vida no topo.
     * O limite do azul é baixo de propósito: com a vida do personagem baixa a barra encolhe e o limite antigo (8%) falhava.
     */
    fun isCombat(): Boolean = hud() > 0.02 && purple() > 0.10

    /**
     * Versão folgada, só usada quando o bot JÁ está em combate: basta um dos dois sinais (e bem fraco).
     * Durante especiais e efeitos a barra de vida some um tempo, e o critério normal falhava no meio da luta.
     */
    fun isCombatLoose(): Boolean = hud() > 0.008 || purple() > 0.04

    /** Brilho médio (0..1) do slot de poder 1 (direita), 2 (meio) ou 3 (esquerda). */
    fun slotV(i: Int): Double {
        val (a, b) = when (i) { 3 -> 0.08f to 0.18f; 2 -> 0.185f to 0.287f; else -> 0.293f to 0.395f }
        val sub = Mat(hsv, r(a, 0.89f, b, 0.95f))
        val v = Core.mean(sub).`val`[2] / 255.0
        sub.release()
        return v
    }

    /**
     * Fração de rosa/magenta (o círculo com o número) no slot i. Só aparece quando o poder está carregado;
     * slot vazio é só roxo-escuro. Mais confiável que o brilho.
     */
    fun slotPink(i: Int): Double {
        val (a, b) = when (i) { 3 -> 0.08f to 0.18f; 2 -> 0.185f to 0.287f; else -> 0.293f to 0.395f }
        val rect = r(a, 0.87f, b, 0.945f)
        return frac(rect, Scalar(160.0, 90.0, 100.0), Scalar(180.0, 255.0, 255.0)) +
            frac(rect, Scalar(0.0, 90.0, 100.0), Scalar(6.0, 255.0, 255.0))
    }

    /** O slot i (1, 2 ou 3) está com o X vermelho: poder indisponível agora, não adianta tocar nele. */
    fun slotBlocked(i: Int): Boolean {
        val (a, b) = when (i) { 3 -> 0.08f to 0.18f; 2 -> 0.185f to 0.287f; else -> 0.293f to 0.395f }
        val sub = Mat(hsv, r(a, 0.87f, b, 0.945f))
        val x = isRedX(sub)
        sub.release()
        return x
    }

    /** Círculo do mini-evento "TOQUE RÁPIDO": centro em proporção da tela. */
    fun ball(): PointF? {
        val rect = r(0.40f, 0.15f, 0.95f, 0.90f)
        val sub = Mat(gray, rect)
        val blur = Mat()
        Imgproc.GaussianBlur(sub, blur, Size(9.0, 9.0), 2.0)
        val c = Mat()
        Imgproc.HoughCircles(blur, c, Imgproc.HOUGH_GRADIENT, 1.2, 80.0, 120.0, 40.0, 30, 70)
        var res: PointF? = null
        if (c.cols() > 0) {
            val v = c.get(0, 0)
            res = PointF(((rect.x + v[0]) / w).toFloat(), ((rect.y + v[1]) / h).toFloat())
        }
        sub.release(); blur.release(); c.release()
        return res
    }

    /**
     * Minigame de ENCHER A BARRA: um círculo pequeno (raio ~16-34 px na imagem de 800) embaixo de uma barra vertical
     * de contorno claro, no centro-direita da tela (círculo em ~57% da largura, ~62% da altura, no print).
     * Não depende do texto, que some logo. Exige o círculo E uma linha vertical clara logo acima dele.
     * Calibrado em um único print: o bot salva `barra_ok_*` em debug/ para conferir.
     */
    fun barCircle(): PointF? {
        val rect = r(0.48f, 0.52f, 0.67f, 0.76f)
        val sub = Mat(gray, rect)
        val blur = Mat()
        Imgproc.GaussianBlur(sub, blur, Size(5.0, 5.0), 1.5)
        val c = Mat()
        Imgproc.HoughCircles(blur, c, Imgproc.HOUGH_GRADIENT, 1.2, 40.0, 100.0, 26.0, 16, 34)
        var res: PointF? = null
        for (i in 0 until c.cols()) {
            val v = c.get(0, i)
            val px = rect.x + v[0]
            val py = rect.y + v[1]
            val barRect = r(((px - 0.03f * w) / w).toFloat(), ((py - 0.34f * h) / h).toFloat(),
                ((px + 0.04f * w) / w).toFloat(), ((py - 0.10f * h) / h).toFloat())
            val bs = Mat(hsv, barRect)
            val m = Mat()
            Core.inRange(bs, Scalar(0.0, 0.0, 160.0), Scalar(180.0, 120.0, 255.0), m)
            val col = Mat()
            Core.reduce(m, col, 0, Core.REDUCE_AVG, CvType.CV_32F)
            val best = Core.minMaxLoc(col).maxVal / 255.0   // coluna mais clara: uma linha vertical cheia dá perto de 1
            bs.release(); m.release(); col.release()
            if (best >= 0.55) { res = PointF((px / w).toFloat(), (py / h).toFloat()); break }
        }
        sub.release(); blur.release(); c.release()
        return res
    }

    /** Seta de "PASSE O DEDO RÁPIDO": direção (dx,dy; dy=+1 é para baixo), centro (cx,cy) e comprimento, tudo em proporção da tela. */
    class Arrow(val dx: Int, val dy: Int, val cx: Float, val cy: Float, val len: Float)

    /** [minArea]/[minAspect] mais altos servem para usar a seta como gatilho (menos falso positivo). */
    fun arrow(minArea: Double = 600.0, minAspect: Float = 1.5f): Arrow? {
        val rect = r(0.35f, 0.15f, 0.85f, 0.92f)
        val sub = Mat(hsv, rect)
        val m = Mat()
        Core.inRange(sub, Scalar(100.0, 30.0, 150.0), Scalar(135.0, 160.0, 255.0), m)
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        val cs = ArrayList<MatOfPoint>()
        Imgproc.findContours(m.clone(), cs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val best = cs.maxByOrNull { Imgproc.contourArea(it) }
        var out: Arrow? = null
        if (best != null && Imgproc.contourArea(best) > minArea) {
            val bb = Imgproc.boundingRect(best)
            val ar = maxOf(bb.width, bb.height).toFloat() / minOf(bb.width, bb.height).coerceAtLeast(1)
            if (ar >= minAspect) {   // seta é comprida; manchas redondas do cenário não contam
                val mm = Mat(m, bb)
                val cx = (rect.x + bb.x + bb.width / 2f) / w
                val cy = (rect.y + bb.y + bb.height / 2f) / h
                if (bb.height >= bb.width) {
                    val n = maxOf(1, bb.height / 4)
                    val top = Core.countNonZero(mm.rowRange(0, n))
                    val bot = Core.countNonZero(mm.rowRange(bb.height - n, bb.height))
                    out = Arrow(0, if (bot > top) 1 else -1, cx, cy, bb.height.toFloat() / h)
                } else {
                    val n = maxOf(1, bb.width / 4)
                    val left = Core.countNonZero(mm.colRange(0, n))
                    val right = Core.countNonZero(mm.colRange(bb.width - n, bb.width))
                    out = Arrow(if (right > left) 1 else -1, 0, cx, cy, bb.width.toFloat() / w)
                }
                mm.release()
            }
        }
        sub.release(); m.release()
        return out
    }

    fun release() { hsv.release(); gray.release() }
}

/**
 * O recorte HSV de um slot de poder mostra o X vermelho (poder indisponível agora)? O X é vermelho puro e vai de ponta a ponta
 * do slot (vermelho nos terços esquerdo E direito); o círculo rosa de poder carregado é só uma mancha no centro e é magenta.
 * Calibrado no print do jogo: X = 0,08 nos dois terços e 0 de magenta; slots normais = 0.
 */
private fun isRedX(sub: Mat): Boolean {
    val red = Mat(); val red2 = Mat(); val mag = Mat()
    Core.inRange(sub, Scalar(0.0, 150.0, 120.0), Scalar(5.0, 255.0, 255.0), red)
    Core.inRange(sub, Scalar(174.0, 150.0, 120.0), Scalar(180.0, 255.0, 255.0), red2)
    Core.bitwise_or(red, red2, red)
    Core.inRange(sub, Scalar(150.0, 90.0, 100.0), Scalar(172.0, 255.0, 255.0), mag)
    val t = maxOf(1, red.cols() / 3)
    val l = Mat(red, Rect(0, 0, t, red.rows()))
    val rr = Mat(red, Rect(red.cols() - t, 0, t, red.rows()))
    val left = Core.mean(l).`val`[0] / 255.0
    val right = Core.mean(rr).`val`[0] / 255.0
    val magenta = Core.mean(mag).`val`[0] / 255.0
    l.release(); rr.release(); red.release(); red2.release(); mag.release()
    return left > 0.03 && right > 0.03 && magenta < 0.02
}

object Heur {
    /**
     * Leitura rápida dos 3 slots de poder: recorta só a faixa de baixo (bem mais barato que o quadro todo).
     * Devolve 6 valores: [0..2] = [slot3, slot2, slot1] carregado? (rosa do número OU brilho acima do limite, e SEM o X vermelho);
     * [3..5] = [slot3, slot2, slot1] está com o X vermelho (indisponível)?
     */
    fun power(bmp: Bitmap, brightTh: Double, pinkTh: Double): BooleanArray {
        val x0 = (0.06f * bmp.width).toInt()
        val x1 = (0.40f * bmp.width).toInt()
        val y0 = (0.86f * bmp.height).toInt()
        val y1 = (0.96f * bmp.height).toInt()
        val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val rgba = Mat(); Utils.bitmapToMat(crop, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val out = BooleanArray(6)
        val slots = listOf(0.08f to 0.18f, 0.185f to 0.287f, 0.293f to 0.395f)   // slot 3, 2, 1
        for ((i, ab) in slots.withIndex()) {
            val sx = ((ab.first * bmp.width) - x0).toInt().coerceIn(0, hsv.cols() - 2)
            val ex = ((ab.second * bmp.width) - x0).toInt().coerceIn(sx + 1, hsv.cols())
            val sy = ((0.87f * bmp.height) - y0).toInt().coerceIn(0, hsv.rows() - 2)
            val ey = ((0.945f * bmp.height) - y0).toInt().coerceIn(sy + 1, hsv.rows())
            val sub = Mat(hsv, Rect(sx, sy, ex - sx, ey - sy))
            val a = Mat(); val b = Mat()
            Core.inRange(sub, Scalar(160.0, 90.0, 100.0), Scalar(180.0, 255.0, 255.0), a)
            Core.inRange(sub, Scalar(0.0, 90.0, 100.0), Scalar(6.0, 255.0, 255.0), b)
            val pink = Core.mean(a).`val`[0] / 255.0 + Core.mean(b).`val`[0] / 255.0
            // Brilho na mesma faixa do Snap.slotV (0,89–0,95), para o limite do painel valer aqui também.
            val by0 = ((0.89f * bmp.height) - y0).toInt().coerceIn(0, hsv.rows() - 2)
            val by1 = ((0.95f * bmp.height) - y0).toInt().coerceIn(by0 + 1, hsv.rows())
            val bsub = Mat(hsv, Rect(sx, by0, ex - sx, by1 - by0))
            val bright = Core.mean(bsub).`val`[2] / 255.0
            bsub.release()
            val blocked = isRedX(sub)
            out[i] = !blocked && (pink > pinkTh || bright > brightTh)
            out[3 + i] = blocked
            sub.release(); a.release(); b.release()
        }
        rgba.release(); rgb.release(); hsv.release(); crop.recycle()
        return out
    }
    fun snap(bmp: Bitmap): Snap {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val ws = 800
        val hs = (bmp.height * ws / bmp.width.toDouble()).toInt()
        val small = Mat()
        Imgproc.resize(rgb, small, Size(ws.toDouble(), hs.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val hsv = Mat(); Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
        val gray = Mat(); Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        rgba.release(); rgb.release(); small.release()
        return Snap(ws, hs, hsv, gray)
    }
}
