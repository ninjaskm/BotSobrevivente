package com.matheus.bot

/** Identificação e posição do poder na barra, compartilhadas pelo combate e pelo combo. */
internal enum class PowerSlot(val number: Int, val loadedIndex: Int, val blockedIndex: Int, val xFraction: Float) {
    ULT(3, 0, 3, 0.13f),
    SECOND(2, 1, 4, 0.236f),
    FIRST(1, 2, 5, 0.343f),
}

/** Política de prioridade, temporização e trava para poderes carregados. */
internal class PowerPolicy(private val config: BotConfig) {
    // Evita insistir num poder que continua parecendo carregado: após 3 tentativas em 1,5 s, bloqueia-o por 2,5 s.
    private val windowStart = LongArray(4)
    private val useCount = IntArray(4)
    private val blockedUntil = LongArray(4)

    var lastPower2At: Long = 0L
        private set

    fun resetTimer(now: Long) {
        lastPower2At = now
    }

    /**
     * Recebe o formato de `Heur.power`: carregados [3, 2, 1], seguidos pelos X [3, 2, 1].
     * Reserva o primeiro poder elegível na ordem ult, poder 2 e poder 1.
     */
    fun select(reading: BooleanArray, now: Long): PowerSlot? {
        if (reading.size < 6) return null

        val candidates = ArrayList<PowerSlot>(3)
        if (config.ult && reading[PowerSlot.ULT.loadedIndex]) candidates += PowerSlot.ULT
        if (reading[PowerSlot.SECOND.loadedIndex]) candidates += PowerSlot.SECOND
        val firstReady = reading[PowerSlot.FIRST.loadedIndex]
        val secondBlocked = reading[PowerSlot.SECOND.blockedIndex]
        val timedFirst = config.power1AfterSec > 0 &&
            now - lastPower2At > config.power1AfterSec * 1000L
        if (firstReady && (secondBlocked || timedFirst)) candidates += PowerSlot.FIRST

        for (slot in candidates) if (allow(slot, now)) return slot
        return null
    }

    fun recordAction(slot: PowerSlot, now: Long) {
        if (slot == PowerSlot.SECOND) lastPower2At = now
    }

    private fun allow(slot: PowerSlot, now: Long): Boolean {
        val index = slot.number
        if (now < blockedUntil[index]) return false
        if (now - windowStart[index] > 1500L) {
            windowStart[index] = now
            useCount[index] = 0
        }
        useCount[index]++
        if (useCount[index] > 3) {
            blockedUntil[index] = now + 2500L
            useCount[index] = 0
            Session.inc("poder${slot.number}_travado")
            return false
        }
        Session.inc("poder${slot.number}")
        return true
    }
}
