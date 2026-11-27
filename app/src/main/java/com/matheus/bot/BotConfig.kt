package com.matheus.bot

import android.content.Context

/** Configurações salvas no aparelho. */
class BotConfig(ctx: Context) {
    private val p = ctx.getSharedPreferences("bot", Context.MODE_PRIVATE)

    /** Depois de N lutas, vai em "Parar e receber". 0 = só para quando o time morrer. */
    var fightTarget: Int
        get() = p.getInt("fightTarget", 0)
        set(v) = p.edit().putInt("fightTarget", v).apply()

    /** Bloqueio com 2 dedos. Pronto, mas desligado por padrão. */
    var block: Boolean
        get() = p.getBoolean("block", false)
        set(v) = p.edit().putBoolean("block", v).apply()

    /** Poder 3 (ult). Desligado por padrão. */
    var ult: Boolean
        get() = p.getBoolean("ult", false)
        set(v) = p.edit().putBoolean("ult", v).apply()

    /** Usa o poder 1 se ficar N segundos sem o poder 2. 0 = nunca. */
    var power1AfterSec: Int
        get() = p.getInt("power1AfterSec", 0)
        set(v) = p.edit().putInt("power1AfterSec", v).apply()

    /**
     * PING PONG, modo manual: antecipação TOTAL (ms) entre o bot decidir tocar e o toque valer (cobre a captura e o atraso do toque).
     * Quando o carimbo do quadro é confiável, o bot desconta a idade medida do quadro e usa só o resto como atraso do toque.
     */
    var pingLeadMs: Int
        get() = p.getInt("pingLeadMs", 90)
        set(v) = p.edit().putInt("pingLeadMs", v).apply()

    /** PING PONG, modo automático: ajuste fino (ms, pode ser negativo) somado ao atraso do toque medido. */
    var pingTrimMs: Int
        get() = p.getInt("pingTrimMs", 0)
        set(v) = p.edit().putInt("pingTrimMs", v).apply()

    /**
     * PING PONG: usa o atraso do toque medido (mediana de `gesture_overhead`) em vez de `pingLeadMs`. Desligado por padrão até você
     * conferir em debug/pingpong_acertos.txt que a previsão acerta (o atraso medido inclui a espera depois do toque e pode passar do real).
     */
    var pingAuto: Boolean
        get() = p.getBoolean("pingAuto", false)
        set(v) = p.edit().putBoolean("pingAuto", v).apply()

    /** Quantos arrastos seguidos por rodada do combo (spam). */
    var comboSwipes: Int
        get() = p.getInt("comboSwipes", 8)
        set(v) = p.edit().putInt("comboSwipes", v).apply()

    /** Arrastos enviados juntos num único gesto (1 = um por vez, como antes; máximo 4). Menos ida e volta ao sistema, mas o combo só pode ser interrompido entre lotes. */
    var comboBatch: Int
        get() = p.getInt("comboBatch", 3)
        set(v) = p.edit().putInt("comboBatch", v).apply()

    /** Pausa (ms) entre arrastos do combo. 0 = o mais rápido possível. */
    var comboGapMs: Int
        get() = p.getInt("comboGapMs", 0)
        set(v) = p.edit().putInt("comboGapMs", v).apply()

    /** Brilho mínimo (%) do slot para considerar o poder carregado. */
    var powerPct: Int
        get() = p.getInt("powerPct", 55)
        set(v) = p.edit().putInt("powerPct", v).apply()

    /** Minigame de encher a barra. Desligue se ele estiver atrapalhando o combo (disparo falso). */
    var barGame: Boolean
        get() = p.getBoolean("barGame", true)
        set(v) = p.edit().putBoolean("barGame", v).apply()

    /** Modo de coleta: durante o combate grava quadros reduzidos e um .json de leitura em debug/dataset/ (até 4 por segundo). */
    var record: Boolean
        get() = p.getBoolean("record", false)
        set(v) = p.edit().putBoolean("record", v).apply()

    /** Tamanho da captura em % da tela (30 a 100). 100 = como sempre foi. Vale ao iniciar a captura. Menor = quadros mais baratos, mas o OCR enxerga letra menor. */
    var captureScalePct: Int
        get() = p.getInt("captureScalePct", 100)
        set(v) = p.edit().putInt("captureScalePct", v).apply()

    /**
     * Reconhecer o personagem pela ARTE da carta na montagem de times (CardArt):
     * 0 = desligado (só OCR, como antes); 1 = a arte só AJUDA quando o OCR não achou a carta; 2 = também REJEITA a carta que o OCR achou
     * se a arte for claramente de outra versão (valem as duas primeiras rodadas de busca; a 3ª volta só com o OCR).
     */
    var artMode: Int
        get() = p.getInt("artMode", 2)
        set(v) = p.edit().putInt("artMode", v).apply()

    /** Pontuação mínima da arte (%) para ACEITAR uma carta que o OCR não leu. Ajuste olhando debug/arte_scores.txt. */
    var artAccept: Int
        get() = p.getInt("artAccept", 60)
        set(v) = p.edit().putInt("artAccept", v).apply()

    /** Pontuação (%) abaixo da qual a arte REJEITA a carta que o OCR achou (modo 2). */
    var artVeto: Int
        get() = p.getInt("artVeto", 30)
        set(v) = p.edit().putInt("artVeto", v).apply()

    /**
     * Montagem de times: segundos arrastando a lista para UM lado antes de parar, analisar e inverter a direção (esquerda -> direita -> esquerda).
     * A rodada também acaba antes se a lista parar de andar (chegou ao fim). Era 50 s fixos.
     */
    var phaseSec: Int
        get() = p.getInt("phaseSec", 15)
        set(v) = p.edit().putInt("phaseSec", v).apply()
}
