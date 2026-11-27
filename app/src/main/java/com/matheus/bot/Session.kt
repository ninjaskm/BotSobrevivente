package com.matheus.bot

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Contadores da sessão (desde que o app abriu): lutas, minigames vistos/acertados/falsos, poderes usados, pausas, tempo em combate...
 * Grava debug/sessao_<inicio>.txt (sobrescreve, no máximo 1 vez por minuto), então o arquivo não cresce.
 * O DebugStore nunca apaga arquivos "sessao*". Serve para comparar uma versão com a outra com número, não com impressão.
 */
object Session {
    private val c = ConcurrentHashMap<String, AtomicLong>()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var lastFlush = 0L

    fun inc(tag: String) { add(tag, 1L) }
    fun add(tag: String, v: Long) { c.getOrPut(tag) { AtomicLong() }.addAndGet(v) }

    fun flush(dir: File, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < 60_000) return
        lastFlush = now
        runCatching {
            val min = (now - startedAt) / 60_000.0
            val sb = StringBuilder()
            sb.append("# sessao iniciada ${java.util.Date(startedAt)}  duracao=%.1f min  (atualizado ${java.util.Date(now)})\n".format(min))
            for ((k, v) in c.entries.sortedBy { it.key }) {
                if (k.endsWith("_ms")) sb.append("%-28s %.1f s\n".format(k, v.get() / 1000.0))
                else sb.append("%-28s %d\n".format(k, v.get()))
            }
            val lutas = c["lutas"]?.get() ?: 0L
            if (lutas > 0 && min > 0) sb.append("%-28s %.2f lutas/min\n".format("ritmo", lutas / min))
            File(dir, "sessao_$startedAt.txt").writeText(sb.toString())
        }
    }
}
