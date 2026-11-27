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

    /** Atraso (ms) antes do toque no mini-evento PING PONG (texto do jogo: "TOQUE PARA ARREMESSAR"). */
    var tossDelayMs: Int
        get() = p.getInt("tossDelayMs", 600)
        set(v) = p.edit().putInt("tossDelayMs", v).apply()

    /** PING PONG: quantos ms ANTES de o marcador chegar ao centro o bot manda o toque (cobre a captura e o atraso do toque). Ajuste fino. */
    var pingLeadMs: Int
        get() = p.getInt("pingLeadMs", 90)
        set(v) = p.edit().putInt("pingLeadMs", v).apply()

    /** Quantos arrastos seguidos por rodada do combo (spam). */
    var comboSwipes: Int
        get() = p.getInt("comboSwipes", 8)
        set(v) = p.edit().putInt("comboSwipes", v).apply()

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
}
