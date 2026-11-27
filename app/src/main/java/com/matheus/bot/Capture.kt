package com.matheus.bot

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection

/** Captura a tela (MediaProjection) em tamanho fixo paisagem. */
class Capture(projection: MediaProjection, val w: Int, val h: Int, dpi: Int) {
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay = projection.createVirtualDisplay(
        "bot", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface, null, null,
    )
    private var last: Bitmap? = null
    private var lastWallMs = 0L

    /**
     * O carimbo do último quadro novo parece estar na mesma base de System.nanoTime() (idade entre 0 e 1 s)?
     * Se sim, [latestFrame] devolve o horário REAL da captura; se não, devolve o horário em que o bot pegou o quadro.
     */
    @Volatile var tsValid = false
        private set

    /**
     * Último quadro disponível junto com o horário (System.currentTimeMillis) em que ele foi CAPTURADO, lidos juntos
     * (dois leitores ao mesmo tempo, como o vigia e o ping pong, nunca recebem quadro de um e horário do outro).
     * Se a tela não mudou, devolve o quadro anterior com o horário anterior.
     */
    @Synchronized
    fun latestFrame(): Pair<Bitmap, Long>? {
        val img = reader.acquireLatestImage()
        if (img == null) {
            val l = last ?: return null
            return l to lastWallMs
        }
        Perf.gap("capture_gap")
        val t0 = System.nanoTime()
        try {
            val ageNs = t0 - img.timestamp
            val ok = ageNs in 0L..1_000_000_000L
            tsValid = ok
            val wall = System.currentTimeMillis()
            lastWallMs = if (ok) wall - ageNs / 1_000_000L else wall
            if (ok) {
                Perf.frameAgeMs.add(ageNs / 1_000_000L)
                Perf.addUs("frame_age", ageNs / 1000)
            }
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * w
            val wide = Bitmap.createBitmap(w + rowPad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(p.buffer)
            last = if (rowPad == 0) wide else Bitmap.createBitmap(wide, 0, 0, w, h)
        } finally {
            img.close()
            Perf.addUs("capture_copy", (System.nanoTime() - t0) / 1000)
        }
        val l = last ?: return null
        return l to lastWallMs
    }

    /** Último quadro disponível (se a tela não mudou, devolve o anterior). */
    fun latest(): Bitmap? = latestFrame()?.first

    fun release() {
        display.release()
        reader.close()
    }
}
