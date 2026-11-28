package com.matheus.bot

import android.content.Context

/** Configurações salvas no aparelho. */
class BotConfig(ctx: Context) {
    private val p = ctx.getSharedPreferences("bot", Context.MODE_PRIVATE)

    init {
        // Atualiza instalações que ainda estavam nos padrões antigos (8 arrastos em lotes de 3), sem sobrescrever
        // valores personalizados diferentes desses padrões.
        if (!p.getBoolean("comboPattern2x2x2Applied", false)) {
            val edit = p.edit()
            if (p.getInt("comboSwipes", 8) == 8) edit.putInt("comboSwipes", 6)
            if (p.getInt("comboBatch", 3) == 3) edit.putInt("comboBatch", 2)
            edit.putBoolean("comboPattern2x2x2Applied", true).apply()
        }
        // Atualiza o padrão de busca para 17 s também em instalações existentes, sem alterar tempos personalizados.
        if (!p.getBoolean("phaseSec17DefaultApplied", false)) {
            val edit = p.edit()
            if (p.getInt("phaseSec", 15) == 15) edit.putInt("phaseSec", 17)
            edit.putBoolean("phaseSec17DefaultApplied", true).apply()
        }
    }

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

    /** Antecipação aprendida pelo ping pong. -1 significa que ainda não houve um acerto medido. */
    internal var pingLearnedLeadMs: Int
        get() = p.getInt("pingLearnedLeadMs", -1)
        set(v) = p.edit().putInt("pingLearnedLeadMs", v).apply()

    /** Quantos arrastos seguidos por rodada do combo (spam). */
    var comboSwipes: Int
        get() = p.getInt("comboSwipes", 6)
        set(v) = p.edit().putInt("comboSwipes", v).apply()

    /** Arrastos enviados juntos num único gesto (1 = um por vez, como antes; máximo 4). Menos ida e volta ao sistema, mas o combo só pode ser interrompido entre lotes. */
    var comboBatch: Int
        get() = p.getInt("comboBatch", 2)
        set(v) = p.edit().putInt("comboBatch", v).apply()

    /** Pausa configurada entre arrastos do combo. 0 = pausa automática calculada no Brain. */
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
     * Montagem de times: segundos arrastando a lista para UM lado antes de parar, analisar e inverter a direção.
     * A rodada também acaba antes se a lista parar de andar (chegou ao fim). Era 50 s fixos.
     */
    var phaseSec: Int
        get() = p.getInt("phaseSec", 17)
        set(v) = p.edit().putInt("phaseSec", v).apply()
}
