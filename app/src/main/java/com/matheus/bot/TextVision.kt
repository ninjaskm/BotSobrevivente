package com.matheus.bot

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Normalizer
import kotlin.coroutines.resume

/** Uma palavra/linha lida na tela. x,y,w,h em proporção da tela (0..1). */
class Word(val text: String, val cx: Float, val cy: Float, val w: Float, val h: Float)

/** Tudo que foi lido num quadro. */
class Screen(val words: List<Word>) {
    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Contém a chave, ou tem uma palavra com no máximo 1 letra errada (só para chaves longas). */
    private fun matches(text: String, key: String): Boolean {
        if (text.contains(key)) return true
        if (key.length < 6) return false
        return text.split(' ').any { it.length in (key.length - 1)..(key.length + 1) && lev(it, key) <= 1 }
    }

    /** Maior palavra que combina com [key] (sem acento/maiúscula), opcionalmente dentro de uma região. */
    fun find(
        key: String,
        xMin: Float = 0f, xMax: Float = 1f, yMin: Float = 0f, yMax: Float = 1f,
    ): Word? = words.filter { matches(it.text, key) && it.cx in xMin..xMax && it.cy in yMin..yMax }
        .maxByOrNull { it.w * it.h }

    fun has(key: String, xMin: Float = 0f, xMax: Float = 1f, yMin: Float = 0f, yMax: Float = 1f) =
        find(key, xMin, xMax, yMin, yMax) != null

    fun hasAny(vararg keys: String) = keys.any { has(it) }
}

/** OCR do Google ML Kit (offline, modelo embutido). Reconhece os botões pelo texto. */
class TextVision {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        /** MAIÚSCULA, sem acento e sem símbolos: "Avançar!" -> "AVANCAR". */
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .uppercase()
                .replace(Regex("[^A-Z0-9 ]"), "")
                .trim()
    }

    /** Lê o quadro inteiro (ou só um recorte em proporção) e devolve palavras e linhas normalizadas. */
    suspend fun read(bmp: Bitmap, l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f): Screen {
        val x = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = ((r - l) * bmp.width).toInt().coerceIn(1, bmp.width - x)
        val h = ((b - t) * bmp.height).toInt().coerceIn(1, bmp.height - y)
        val cropped = if (x == 0 && y == 0 && w == bmp.width && h == bmp.height) bmp
        else Bitmap.createBitmap(bmp, x, y, w, h)
        // OCR em 2400 px de largura é lento; os botões são grandes, então 1280 px bastam. As posições voltam em proporção.
        val maxW = 1280
        val inv = if (cropped.width > maxW) cropped.width.toFloat() / maxW else 1f
        val src = if (inv > 1f)
            Bitmap.createScaledBitmap(cropped, maxW, (cropped.height / inv).toInt().coerceAtLeast(1), true)
        else cropped
        val res = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { c ->
            rec.process(InputImage.fromBitmap(src, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resume(null) }
        }
        val out = ArrayList<Word>()
        res?.textBlocks?.forEach { blk ->
            blk.lines.forEach { line ->
                val bb = line.boundingBox ?: return@forEach
                val txt = norm(line.text)
                if (txt.isNotEmpty()) out += Word(
                    txt,
                    (x + bb.exactCenterX() * inv) / bmp.width, (y + bb.exactCenterY() * inv) / bmp.height,
                    bb.width() * inv / bmp.width, bb.height() * inv / bmp.height,
                )
                line.elements.forEach { el ->
                    val eb = el.boundingBox ?: return@forEach
                    val et = norm(el.text)
                    if (et.isNotEmpty() && et != txt) out += Word(
                        et,
                        (x + eb.exactCenterX() * inv) / bmp.width, (y + eb.exactCenterY() * inv) / bmp.height,
                        eb.width() * inv / bmp.width, eb.height() * inv / bmp.height,
                    )
                }
            }
        }
        return Screen(out)
    }
}
