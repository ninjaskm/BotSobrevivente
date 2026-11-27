package com.matheus.bot

import java.io.File

/** Limpeza das pastas de debug: sem isso os prints e logs crescem sem parar. */
object DebugStore {
    /** Arquivos que nunca são apagados (medições e logs de calibração). */
    private val keepDefault: (File) -> Boolean = { f ->
        f.name.startsWith("perf") || f.name.startsWith("pingpong_acertos") || f.name.startsWith("sessao") || f.name.startsWith("arte_scores")
    }

    /**
     * Mantém no máximo [maxFiles] arquivos e [maxBytes] bytes diretamente dentro de [dir] (não entra em subpastas),
     * apagando os mais antigos primeiro. Os que [keep] aceita ficam de fora da conta e nunca são apagados.
     */
    fun trim(
        dir: File,
        maxFiles: Int = 500,
        maxBytes: Long = 300L * 1024 * 1024,
        keep: (File) -> Boolean = keepDefault,
    ) {
        runCatching {
            val all = dir.listFiles { f -> f.isFile } ?: return@runCatching
            val sorted = all.filterNot(keep).sortedBy { it.lastModified() }
            var count = sorted.size
            var bytes = sorted.sumOf { it.length() }
            for (f in sorted) {
                if (count <= maxFiles && bytes <= maxBytes) break
                val len = f.length()
                if (f.delete()) { count--; bytes -= len }
            }
        }
    }
}
