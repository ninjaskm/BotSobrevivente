package com.matheus.bot

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Mediana das últimas [cap] amostras (em ms). Serve para o bot calibrar a antecipação do toque com números reais. */
class Rolling(private val cap: Int = 31) {
    private val buf = LongArray(cap)
    private var n = 0
    private var total = 0L

    @Synchronized fun add(v: Long) { buf[(total % cap).toInt()] = v; total++; if (n < cap) n++ }
    @Synchronized fun count(): Int = n
    @Synchronized fun median(): Long? {
        if (n == 0) return null
        val a = buf.copyOf(n)
        a.sort()
        return a[n / 2]
    }
}

/**
 * Medição de tempo (só para ajustar o bot com número real, não muda o comportamento). Guarda por etiqueta quantas vezes,
 * a média e o máximo (em ms) e, a cada 15 s, acrescenta um bloco em debug/perf.txt e zera (cada bloco é uma janela de 15 s).
 *  - capture_gap: intervalo entre quadros NOVOS da tela (1000 / média = quadros por segundo);  capture_copy: copiar o quadro novo
 *  - frame_age: idade do quadro quando o bot o pega (agora - carimbo do quadro). Só entra se o carimbo parece válido
 *  - ocr WxH: uma leitura de texto (ML Kit) nesse tamanho de imagem (inclui a espera na fila, se houver)
 *  - snap / power / watch_power / ping_profile: análises de cor e forma
 *  - gesture_overhead: do pedido do toque/arrasto até o Android confirmar, menos a duração do próprio gesto (= atraso do toque)
 *  - gesture_overhead_burst / gesture_overhead_batch: o mesmo para a rajada de toques e para o lote de arrastos
 *  - ping_gap: intervalo entre quadros no PING PONG
 * No cabeçalho de cada bloco vão as medianas recentes usadas na autocalibração do ping pong.
 */
object Perf {
    private class Stat { var n = 0L; var totalUs = 0L; var maxUs = 0L }
    private val stats = ConcurrentHashMap<String, Stat>()
    private val lastAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastFlush = 0L

    /** Atraso (ms) dos toques e arrastos curtos, além da duração do gesto. Mediana das últimas 31. */
    val overheadMs = Rolling(31)
    /** Idade (ms) dos quadros quando o bot os pega (captura até a leitura). Mediana das últimas 31. */
    val frameAgeMs = Rolling(31)

    fun addUs(tag: String, us: Long) {
        val s = stats.getOrPut(tag) { Stat() }
        synchronized(s) { s.n++; s.totalUs += us; if (us > s.maxUs) s.maxUs = us }
    }

    /** Registra o intervalo desde a última chamada com a mesma etiqueta. */
    fun gap(tag: String) {
        val t = System.nanoTime()
        val prev = lastAt.put(tag, t)
        if (prev != null) addUs(tag, (t - prev) / 1000)
    }

    inline fun <T> time(tag: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { addUs(tag, (System.nanoTime() - t0) / 1000) }
    }

    /** No máximo a cada 15 s: grava as estatísticas da janela em debug/perf.txt e zera. Passou de 1 MB, o arquivo vira perf_old.txt. */
    fun flush(dir: File) {
        val now = System.currentTimeMillis()
        if (lastFlush == 0L) { lastFlush = now; return }
        if (now - lastFlush < 15_000) return
        lastFlush = now
        runCatching {
            val sb = StringBuilder("# ${java.util.Date(now)}  overhead_toque_med=${overheadMs.median() ?: -1}ms  idade_quadro_med=${frameAgeMs.median() ?: -1}ms\n")
            for ((tag, s) in stats.entries.sortedBy { it.key }) {
                synchronized(s) {
                    if (s.n > 0) sb.append("%-26s n=%-5d media=%.1f ms  max=%.1f ms\n".format(tag, s.n, s.totalUs / s.n / 1000.0, s.maxUs / 1000.0))
                    s.n = 0; s.totalUs = 0; s.maxUs = 0
                }
            }
            val f = File(dir, "perf.txt")
            if (f.length() > 1_000_000) {
                val old = File(dir, "perf_old.txt")
                old.delete()
                f.renameTo(old)
            }
            File(dir, "perf.txt").appendText(sb.toString())
        }
    }
}
