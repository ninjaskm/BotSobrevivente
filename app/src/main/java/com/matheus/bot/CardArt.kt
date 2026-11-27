package com.matheus.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Reconhece o personagem pela ARTE da carta (sem ler texto): compara um recorte da arte de referência com a faixa da lista de baixo
 * (template matching do OpenCV, em 3 escalas). Serve para separar as VERSÕES da mesma carta (Apocalipse "Contenção" x "Noite Mais Densa",
 * Nevasca nível 50 x "Regime", Batman Ninja x "Foice", Caçador nível 50 x 45) sem depender do OCR ler o subtítulo sobre arte brilhante.
 *
 * As referências saem de duas fotos suas em assets/cartas/ (tela de escolha de personagens, tela cheia):
 *  - equipe1.jpg: Flash Reverso, Apocalipse (Contenção) e Nevasca nos quadrados; Batman Ninja, Cavaleiro Arkham e Caçador (Noite Mais Densa)
 *    aparecem também na lista de baixo (mesma renderização que o bot enxerga);
 *  - equipe2.jpg: Batman, Cavaleiro Arkham e Caçador de Marte nos quadrados.
 * Todas as posições abaixo são PROPORÇÕES da foto (a foto tem a mesma proporção da captura do bot).
 *
 * Pontuação = correlação normalizada (-1..1; ~1 = mesma arte). Os limites de aceitar/rejeitar ficam em BotConfig e se ajustam
 * olhando debug/arte_scores.txt. Se algo falhar ao carregar, [has] devolve false e o bot segue só com o OCR, como antes.
 */
object CardArt {
    /** Resultado: centro horizontal da carta (proporção da tela) e a pontuação. */
    class Hit(val cx: Float, val score: Float)

    private class Ref(val key: String, val asset: String, val cx: Float, val ya: Float, val yb: Float)

    // Metade da largura do recorte (proporção da largura da foto): ~75 px de 1568, dentro da arte (a carta tem ~197 px de largura).
    private const val HALF_W = 0.048f

    // Recortes só da ARTE: sem o algarismo romano e o nível (em cima), sem o subtítulo, o nome e os números (embaixo).
    // Quadrado do time: arte entre y 0,078 e 0,290 da altura. Lista de baixo: entre 0,51 e 0,715.
    private val refs = listOf(
        Ref("REVERSO", "cartas/equipe1.jpg", 0.1375f, 0.078f, 0.290f),
        Ref("APOCALIPSE", "cartas/equipe1.jpg", 0.306f, 0.078f, 0.290f),
        Ref("NEVASCA", "cartas/equipe1.jpg", 0.476f, 0.078f, 0.290f),
        Ref("BATMAN", "cartas/equipe2.jpg", 0.1375f, 0.078f, 0.290f),
        Ref("CAVALEIRO", "cartas/equipe2.jpg", 0.306f, 0.078f, 0.290f),
        Ref("DE MARTE", "cartas/equipe2.jpg", 0.476f, 0.078f, 0.290f),
        // Os mesmos três do time 2, vistos na LISTA de baixo da foto do time 1 (esta é a aparência que o bot realmente compara).
        Ref("BATMAN", "cartas/equipe1.jpg", 0.1218f, 0.51f, 0.715f),
        Ref("CAVALEIRO", "cartas/equipe1.jpg", 0.262f, 0.51f, 0.715f),
        Ref("DE MARTE", "cartas/equipe1.jpg", 0.5427f, 0.51f, 0.715f),
    )

    /** Largura de trabalho (a foto e o quadro são reduzidos a isto: rápido e igual dos dois lados). */
    private const val WORK_W = 640
    /** Faixa da tela onde ficam as cartas da lista (proporção da altura). */
    private const val STRIP_Y0 = 0.43f
    private const val STRIP_Y1 = 0.80f
    /** Escalas do recorte: a arte no quadrado do time e na lista nem sempre têm exatamente o mesmo tamanho. */
    private val SCALES = floatArrayOf(0.88f, 1.0f, 1.14f)
    /** O melhor valor é guardado por faixas verticais da tela (NB colunas de 1/NB da largura). */
    private const val NB = 100

    private val templates = HashMap<String, MutableList<Mat>>()
    @Volatile private var ready = false

    // Cache por quadro: o mesmo Bitmap não é refeito (as várias consultas da mesma análise saem de graça).
    private var cacheFrame: Bitmap? = null
    private var strip: Mat? = null
    private val cache = HashMap<String, FloatArray>()

    /** Carrega as referências (chamar depois de OpenCVLoader.initLocal). Qualquer falha só desliga o recurso. */
    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        runCatching {
            val bitmaps = HashMap<String, Mat>()
            for (r in refs) {
                val ref = bitmaps.getOrPut(r.asset) { loadRef(ctx, r.asset) ?: return@runCatching }
                val x0 = ((r.cx - HALF_W) * ref.cols()).roundToInt().coerceIn(0, ref.cols() - 2)
                val x1 = ((r.cx + HALF_W) * ref.cols()).roundToInt().coerceIn(x0 + 1, ref.cols())
                val y0 = (r.ya * ref.rows()).roundToInt().coerceIn(0, ref.rows() - 2)
                val y1 = (r.yb * ref.rows()).roundToInt().coerceIn(y0 + 1, ref.rows())
                val t = Mat(ref, Rect(x0, y0, x1 - x0, y1 - y0)).clone()
                templates.getOrPut(r.key) { ArrayList() }.add(t)
            }
            bitmaps.values.forEach { it.release() }
            ready = true
        }
    }

    private fun loadRef(ctx: Context, asset: String): Mat? = runCatching {
        val bmp = ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
        val out = toWork(bmp)
        bmp.recycle()
        out
    }.getOrNull()

    /** Bitmap -> Mat RGB com largura WORK_W (mantém a proporção). */
    private fun toWork(bmp: Bitmap): Mat {
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val h = (rgb.rows() * WORK_W / rgb.cols().toDouble()).roundToInt().coerceAtLeast(1)
        val out = Mat(); Imgproc.resize(rgb, out, Size(WORK_W.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        rgba.release(); rgb.release()
        return out
    }

    /** Há referência de arte para este personagem (chave do Target)? */
    fun has(key: String): Boolean = ready && templates.containsKey(key)

    private fun buildStrip(frame: Bitmap): Mat {
        val y0 = (STRIP_Y0 * frame.height).toInt().coerceIn(0, frame.height - 2)
        val y1 = (STRIP_Y1 * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        val crop = Bitmap.createBitmap(frame, 0, y0, frame.width, y1 - y0)
        val m = toWork(crop)
        crop.recycle()
        return m
    }

    /** Melhor pontuação por faixa horizontal da tela para o personagem [key] neste quadro (null = sem referência). */
    @Synchronized
    private fun binsFor(frame: Bitmap, key: String): FloatArray? {
        if (!ready) return null
        val tpls = templates[key] ?: return null
        if (cacheFrame !== frame) {
            cache.clear(); strip?.release()
            strip = runCatching { buildStrip(frame) }.getOrNull()
            cacheFrame = frame
        }
        cache[key]?.let { return it }
        val s = strip ?: return null
        val bins = FloatArray(NB) { -1f }
        runCatching {
            for (t in tpls) for (sc in SCALES) {
                val tw = (t.cols() * sc).roundToInt()
                val th = (t.rows() * sc).roundToInt()
                if (tw < 8 || th < 8 || tw >= s.cols() || th > s.rows()) continue
                val tp = if (sc == 1.0f) t else Mat().also {
                    Imgproc.resize(t, it, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                }
                val res = Mat()
                Imgproc.matchTemplate(s, tp, res, Imgproc.TM_CCOEFF_NORMED)
                val col = Mat()
                Core.reduce(res, col, 0, Core.REDUCE_MAX, CvType.CV_32F)
                val n = col.cols()
                val arr = FloatArray(n)
                col.get(0, 0, arr)
                for (x in 0 until n) {
                    val v = arr[x]
                    if (v.isNaN() || v.isInfinite()) continue
                    val cx = (x + tw / 2f) / s.cols()
                    val b = (cx * NB).toInt().coerceIn(0, NB - 1)
                    if (v > bins[b]) bins[b] = v
                }
                res.release(); col.release()
                if (tp !== t) tp.release()
            }
        }
        cache[key] = bins
        return bins
    }

    /** A melhor carta com a arte de [key] na faixa da lista (a de maior pontuação), ou null se não há referência. */
    fun best(frame: Bitmap, key: String): Hit? {
        val bins = binsFor(frame, key) ?: return null
        var bi = -1
        var bv = -1f
        for (i in bins.indices) if (bins[i] > bv) { bv = bins[i]; bi = i }
        if (bi < 0) return null
        return Hit((bi + 0.5f) / NB, bv)
    }

    /** Pontuação da arte de [key] perto de [cx] (±[halfW] da largura da tela); null se não há referência. */
    fun scoreNear(frame: Bitmap, key: String, cx: Float, halfW: Float = 0.07f): Float? {
        val bins = binsFor(frame, key) ?: return null
        val a = ((cx - halfW) * NB).toInt().coerceIn(0, NB - 1)
        val b = ((cx + halfW) * NB).toInt().coerceIn(a, NB - 1)
        var v = -1f
        for (i in a..b) if (bins[i] > v) v = bins[i]
        return v.coerceAtLeast(0f)
    }
}
