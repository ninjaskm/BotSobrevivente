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

    /** Último quadro disponível (se a tela não mudou, devolve o anterior). */
    @Synchronized
    fun latest(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return last
        try {
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * w
            val wide = Bitmap.createBitmap(w + rowPad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(p.buffer)
            last = if (rowPad == 0) wide else Bitmap.createBitmap(wide, 0, 0, w, h)
        } finally {
            img.close()
        }
        return last
    }

    fun release() {
        display.release()
        reader.close()
    }
}
