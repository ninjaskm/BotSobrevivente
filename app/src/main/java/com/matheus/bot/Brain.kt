package com.matheus.bot

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/** Os minigames dos especiais. A decisão de qual é fica toda em Brain.miniOf (um único lugar). */
private enum class Mini { PING_PONG, SETA, TOQUE, BARRA }

private enum class Sc { COMBATE, AVANCAR, CONTINUAR, FECHAR, CONFIRMA, LOBBY_MORTO, LOBBY_VIVO, ROLETA, HOME, EQUIPE_VAZIA, DETALHE, DESCONHECIDA }

/**
 * "Cérebro": a cada ciclo LÊ a tela (texto dos botões + formas/cores), descobre em que
 * estado está e age. Nunca age sem reconhecer a tela. Nada de marcar botões na mão.
 */
class Brain(
    private val scope: CoroutineScope,
    private val cap: Capture,
    private val text: TextVision,
    private val cfg: BotConfig,
    private val debugDir: File,
    private val say: (String) -> Unit,
) {
    /** Mostra/esconde o painel flutuante (ele aparece na captura e cobre o quadrado 1 na montagem de times). */
    var showUi: (Boolean) -> Unit = {}
    @Volatile private var pausedFlag = true
    /** Chamado quando o estado pausado/rodando muda (inclusive quando o próprio bot pausa), para o botão do painel acompanhar. */
    var onPausedChange: (Boolean) -> Unit = {}
    private var paused: Boolean
        get() = pausedFlag
        set(v) { if (pausedFlag != v) { pausedFlag = v; onPausedChange(v) } }
    val isPaused: Boolean get() = pausedFlag
    private var job: Job? = null
    private var fights = 0
    private var unknown = 0
    private var deadVotes = 0
    private var last: Sc? = null
    private var stopAfter = false
    private var lastCenterTap = 0L
    private var lastPower2 = 0L
    private var lastInfo = 0L
    private var lastEvCheck = 0L
    private var tossDebug = 0
    private var arrowDebug = 0
    private var evDebug = 0
    private var lastProbe = 0L
    /** O minigame que acabou de rodar parou porque já apareceu OUTRO: o próximo não deve esperar o bloqueio de 0,8 s. */
    private var chainEv = false
    private var noCombat = 0
    /** Toques seguidos para fechar a tela de detalhes do personagem (abriu sem querer). */
    private var detailTaps = 0
    private var team = 1
    private val swap = TeamSwap(cap, text) { b, tag -> saveDebug(b, tag) }.also { it.say = say }
    private var lastHud = 0.0
    private var lastPur = 0.0
    @Volatile private var evBusy = false
    @Volatile private var evScreen: Screen? = null
    @Volatile private var evAt = 0L
    private var evBlockUntil = 0L
    private var lastShape = 0L
    /** Janela em que um minigame pode aparecer: logo depois de o bot usar um poder (1, 2 ou 3). */
    @Volatile private var evWindowUntil = 0L
    private fun markPower() { evWindowUntil = now() + 8000 }

    // Trava contra "bater no poder para sempre": se o bot toca no MESMO poder mais de 3 vezes em 1,5 s e ele continua parecendo
    // carregado (poder que não dispara: leitura errada do brilho, efeito vermelho sobre o slot, personagem atordoado...), esse poder
    // fica de lado por 2,5 s e o combo volta. Antes, com o poder "carregado" o tempo todo, o bot só tocava nele e nunca arrastava.
    private val powerWinStart = LongArray(4)
    private val powerCnt = IntArray(4)
    private val powerBlockUntil = LongArray(4)
    private fun usePower(slot: Int): Boolean {
        val n = now()
        if (n < powerBlockUntil[slot]) return false
        if (n - powerWinStart[slot] > 1500) { powerWinStart[slot] = n; powerCnt[slot] = 0 }
        powerCnt[slot]++
        if (powerCnt[slot] > 3) { powerBlockUntil[slot] = n + 2500; powerCnt[slot] = 0; return false }
        return true
    }
    private var barDebug = 0
    /** Última detecção da forma da barra (ainda não confirmada) e quando foi. Só vira minigame se aparecer 2x seguidas no mesmo lugar. */
    private var barSeen: PointF? = null
    private var barSeenAt = 0L

    fun start() {
        paused = false
        if (job?.isActive != true) {
            fights = 0; unknown = 0; deadVotes = 0; last = null; stopAfter = false
            job = scope.launch { loop() }
        }
        say("Rodando")
    }

    fun pause() { paused = true; say("Pausado") }
    fun stop() { job?.cancel() }

    private fun now() = System.currentTimeMillis()

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            if (paused) { delay(200); continue }
            val g = BotAccessibilityService.instance
            if (g == null) { say("Acessibilidade desligada"); paused = true; continue }
            val bmp = cap.latest()
            if (bmp == null) { delay(100); continue }
            val snap = Heur.snap(bmp)
            try {
                lastHud = snap.hud(); lastPur = snap.purple()
                val strict = snap.isCombat()
                // Evidência fraca de luta também vale: já em combate (sinal folgado) ou numa "tela desconhecida" com os
                // slots roxos de poder bem visíveis (era o caso do bot ficar preso em "desconhecida" no meio da luta).
                val fightish = strict || (last == Sc.COMBATE && snap.isCombatLoose()) ||
                    (last == Sc.DESCONHECIDA && lastPur > 0.25)
                if (fightish) {
                    // Com evidência fraca, de 1 em 1 s confere pelo texto se não é um menu de verdade (AVANÇAR, FECHAR...).
                    if (!strict && now() - lastProbe > 1000) {
                        lastProbe = now()
                        menuTick(bmp, g, probe = true)
                        if (last != Sc.COMBATE && last != Sc.DESCONHECIDA) continue
                    }
                    noCombat = 0
                    combatTick(bmp, snap, g)
                } else if (last == Sc.COMBATE && noCombat < 25) {
                    // Sem HUD no meio da luta (animação de especial, efeito): NÃO vira "menu" nem fica lendo a tela.
                    // Continua batendo (inofensivo) e só de ~1 s em 1 s pergunta ao OCR se apareceu um botão
                    // (AVANÇAR, FECHAR...), sem mexer no estado se a tela não for reconhecida.
                    noCombat++
                    if (now() - lastProbe > 1000) { lastProbe = now(); menuTick(bmp, g, probe = true) }
                    if (last == Sc.COMBATE) combo(g, bmp.width, bmp.height)   // se o OCR achou um menu, `last` mudou
                } else {
                    menuTick(bmp, g)
                }
            } finally {
                snap.release()
            }
        }
    }

    private fun enter(sc: Sc) {
        if (sc == last) return
        if (sc == Sc.AVANCAR) fights++
        if (sc == Sc.COMBATE) lastPower2 = now()
        unknown = 0
        say("$sc | lutas: $fights")
        last = sc
    }

    private suspend fun tapW(g: BotAccessibilityService, w: Word, bmp: Bitmap) = g.tap(w.cx * bmp.width, w.cy * bmp.height)
    private suspend fun tapPoint(g: BotAccessibilityService, bmp: Bitmap, fx: Float, fy: Float) = g.tap(fx * bmp.width, fy * bmp.height)

    // ---------------- Menus (leitura de texto) ----------------
    private suspend fun menuTick(bmp: Bitmap, g: BotAccessibilityService, probe: Boolean = false) {
        val s = text.read(bmp)
        val avancar = s.find("AVANCAR", yMin = 0.7f)
        val continuar = s.find("CONTINUAR", yMin = 0.75f)
        // Popup "NOVA CARTA" (item ganho na roleta do Coringa etc.): o botão FECHAR fica embaixo do popup.
        val fechar = s.find("FECHAR", yMin = 0.75f)
        val lutar = s.find("LUTAR", 0.45f, 0.78f, 0.30f, 0.65f)
        val receber = s.find("RECEBER", yMin = 0.7f)
        val equipe = s.find("EQUIPE", yMin = 0.7f)
        val sobrev = s.find("SOBREVIVENTE", yMin = 0.7f)

        when {
            // Confirmação do "Parar e receber": só age se reconhecer o texto de aviso (não existe EQUIPE aqui).
            receber != null && equipe == null &&
                (s.has("OPTAR") || s.has("SAIR DO MODO") || s.has("TODAS AS RECOMPENSAS")) -> {
                enter(Sc.CONFIRMA); tapW(g, receber, bmp); stopAfter = true; delay(1500)
            }
            fechar != null -> { enter(Sc.FECHAR); tapW(g, fechar, bmp); delay(800) }
            avancar != null -> { enter(Sc.AVANCAR); tapW(g, avancar, bmp); delay(800) }
            continuar != null -> { enter(Sc.CONTINUAR); tapW(g, continuar, bmp); delay(800) }
            // Tela de detalhes do personagem (abriu sem querer): tem EQUIPAMENTO + MELHORIAS DE SUPORTE / NÍVEL ATUAL / CRÍTICO.
            // Fecha tocando no MEIO do personagem (o quadrado com o modelo 3d, à esquerda), abaixo do painel flutuante do bot.
            // A montagem de times trata essa tela sozinha (TeamSwap.openEquip), por isso isto só roda fora dela.
            s.has("EQUIPAMENTO") && s.hasAny("SUPORTE", "MELHORIAS", "NIVEL ATUAL", "CRITICO") -> {
                if (last != Sc.DETALHE) detailTaps = 0
                enter(Sc.DETALHE)
                detailTaps++
                if (detailTaps > 10) {
                    saveDebug(bmp, "detalhe_nao_fechou")
                    say("Tela de detalhes do personagem não fechou. Pausado.")
                    paused = true
                } else {
                    tapPoint(g, bmp, 0.16f, 0.40f)
                    delay(900)
                }
            }
            receber != null && equipe != null -> lobby(bmp, g, lutar, receber)
            s.has("GIRE") || s.has("SORTEAR") || s.has("RODA", yMax = 0.35f) || s.has("RECOMPENSAS", yMax = 0.35f) -> {
                spinWheel(bmp, g)
            }
            sobrev != null && s.hasAny("BATALHA", "COLECAO", "LOJA") -> {
                enter(Sc.HOME)
                if (stopAfter) { say("Ciclo terminado."); paused = true }
                else { tapW(g, sobrev, bmp); delay(1500) }
            }
            s.has("CRIE SUA EQUIPE") -> {
                enter(Sc.EQUIPE_VAZIA)
                say("Montando o time 1...")
                showUi(false)
                val err = try { delay(700); swap.createTeam1(g) } finally { showUi(true) }
                if (err == null) say("Time 1 pronto (personagens e equipamentos). Seguindo.")
                else { say("$err. Pausado."); paused = true }
            }
            else -> {
                if (probe) return   // só uma espiada durante a luta: tela não reconhecida = continua em combate
                if (stopAfter) {
                    enter(Sc.DESCONHECIDA); unknown++
                    coringa(bmp, g, s)
                } else {
                    // O texto da roleta fica cortado no topo da tela e o OCR geral costuma não ler.
                    // Segunda leitura só da faixa de cima (só em tela desconhecida, para não pesar).
                    val top = text.read(bmp, 0.15f, 0f, 0.85f, 0.32f)
                    // "PARTIDA" (fim da frase "...RECOMPENSAS DA PARTIDA!") costuma ser a única parte legível do texto cortado da roleta;
                    // vale só aqui (tela que nenhum outro botão reconheceu) e só no topo da tela.
                    if (top.hasAny("GIRE", "RODA", "SORTEAR", "RECOMPENSAS") ||
                        top.has("PARTIDA", yMax = 0.25f) || s.has("PARTIDA", yMax = 0.25f)) {
                        spinWheel(bmp, g)
                    } else {
                        enter(Sc.DESCONHECIDA)
                        unknown++
                        if (unknown == 1) {
                            saveDebug(bmp, "desconhecida")
                            say("Tela desconhecida")
                        }
                        // Tela desconhecida: NÃO gira a roleta às cegas. Só continua lendo (OCR geral + faixa de cima) a cada volta e age
                        // normalmente assim que reconhecer a tela. Se ficar tempo demais sem reconhecer, pausa (abaixo).
                        if (unknown > 120) { say("Tela desconhecida por muito tempo. Pausado."); paused = true }
                    }
                }
                delay(250)
            }
        }
        delay(60)
    }

    /** Roleta: arrasto rápido para a esquerda (a seta do jogo aponta para a esquerda). */
    private suspend fun spinSwipe(bmp: Bitmap, g: BotAccessibilityService): Boolean =
        g.swipe(bmp.width * 0.74f, bmp.height * 0.62f, bmp.width * 0.20f, bmp.height * 0.62f, 200)

    private suspend fun spinWheel(bmp: Bitmap, g: BotAccessibilityService) {
        enter(Sc.ROLETA)
        val first = spinSwipe(bmp, g)
        // Segurança: 2º giro logo depois (o 1º pode não pegar). Se o combate já apareceu, não gira de novo.
        delay(700)
        val inCombat = cap.latest()?.let { b ->
            val sn = Heur.snap(b)
            try { sn.isCombat() } finally { sn.release() }
        } ?: false
        val second = if (inCombat) false else spinSwipe(bmp, g)
        val ok = first || second
        // Espera o giro acabar, mas sai assim que o combate aparecer (antes esperava 4,5 s fixos).
        val limit = now() + if (ok) 4500 else 1200
        while (currentCoroutineContext().isActive && now() < limit) {
            delay(150)
            val b = cap.latest() ?: continue
            val s = Heur.snap(b)
            val c = try { s.isCombat() } finally { s.release() }
            if (c) break
        }
    }

    private suspend fun lobby(bmp: Bitmap, g: BotAccessibilityService, lutar: Word?, receber: Word) {
        if (lutar != null) {
            deadVotes = 0
            enter(Sc.LOBBY_VIVO)
            if (cfg.fightTarget > 0 && fights >= cfg.fightTarget) {
                say("Meta de lutas atingida: parar e receber")
                tapW(g, receber, bmp)
            } else {
                tapW(g, lutar, bmp)
            }
            delay(900)
        } else {
            // Sem o botão LUTAR: confirma por 3 leituras seguidas antes de concluir "time morto".
            deadVotes++
            if (deadVotes >= 3) {
                enter(Sc.LOBBY_MORTO)
                if (team == 1) {
                    say("Time 1 morreu. Trocando para o time 2...")
                    showUi(false)
                    val err = try { delay(700); swap.run(g) } finally { showUi(true) }
                    if (err == null) {
                        team = 2
                        deadVotes = 0
                        say("Time 2 escolhido. Seguindo a luta.")
                    } else {
                        say("$err. Pausado.")
                        paused = true   // só pausa se a troca falhou
                    }
                } else {
                    say("Time $team morreu. Fim do ciclo. Pausado.")
                    paused = true
                }
            }
            delay(400)
        }
    }

    /** Última Risada: aceita o prêmio se achar o botão, senão dá 1 toque no centro de tempos em tempos. */
    private suspend fun coringa(bmp: Bitmap, g: BotAccessibilityService, s: Screen) {
        val ok = listOf("ACEITAR", "COLETAR", "RESGATAR", "CONTINUAR", "RECEBER")
            .firstNotNullOfOrNull { s.find(it, yMin = 0.4f) }
        if (ok != null) { tapW(g, ok, bmp); delay(1000); return }
        if (now() - lastCenterTap > 3000) {
            g.tap(bmp.width * 0.5f, bmp.height * 0.5f)
            lastCenterTap = now()
        }
    }

    // ---------------- Combate ----------------
    private suspend fun combatTick(bmp: Bitmap, snap: Snap, g: BotAccessibilityService) {
        enter(Sc.COMBATE)
        val w = bmp.width
        val h = bmp.height

        // Mini-evento: o texto do canto superior direito diz qual é. A leitura (OCR) roda em segundo plano
        // para não interromper o combo; aqui só consumimos o último resultado.
        scanEvent(bmp)
        val ev = evScreen
        if (ev != null && now() - evAt < 1500 && now() >= evBlockUntil) {
            evScreen = null
            val kind = miniOf(ev)
            if (kind != null) {
                val acted: Boolean = when (kind) {
                    Mini.PING_PONG -> { say("Minigame: ping pong"); pingPong(g, w, h); true }
                    Mini.SETA -> { say("Minigame: seta"); swipeEvent(g) }
                    Mini.TOQUE -> touchGame(g, w, h, preferBar = false)
                    Mini.BARRA -> {
                        if (evDebug < 3) { saveDebug(bmp, "evento_barra"); evDebug++ }
                        touchGame(g, w, h, preferBar = true)
                    }
                }
                // Se o texto disparou mas o minigame NÃO existia (a forma não apareceu), bloqueia por 3 s: antes o bloqueio era de
                // 0,4 s e o mesmo texto falso voltava a interromper o combo depois de 1 arrasto, sem parar (combo "do zero" toda hora).
                // Com minigame de verdade: bloqueio curto só para o mesmo texto não disparar de novo logo que ele acaba.
                evBlockUntil = when {
                    !acted -> now() + 3000
                    chainEv || kind == Mini.PING_PONG -> 0L
                    else -> now() + 400
                }
                chainEv = false
                return
            }
        }

        // Gatilho pela FORMA (só a barra de encher), para quando o texto do evento já sumiu ou o OCR não leu: círculo pequeno
        // + barra vertical. A cada ~0,3 s. A seta do PASSE O DEDO NÃO dispara por forma: brilhos azuis do cenário e dos
        // personagens (ex.: Batman) eram lidos como seta e interrompiam o combo; ela só começa pelo TEXTO do evento.
        // CONFIRMAÇÃO: um quadro só não basta (brilhos de efeito imitam círculo + faixa clara e disparavam o spam no meio da luta).
        // Só vira minigame se a forma aparecer 2 leituras seguidas (<0,7 s entre elas) praticamente no mesmo ponto; o minigame de
        // verdade fica vários segundos na tela, o falso some. Enquanto não confirma, o combo segue normal.
        if (cfg.barGame && now() >= evBlockUntil && now() < evWindowUntil && now() - lastShape > 250) {
            lastShape = now()
            val bp = snap.barCircle()
            val prev = barSeen
            if (bp == null) {
                barSeen = null
            } else if (prev != null && now() - barSeenAt < 700 &&
                kotlin.math.abs(prev.x - bp.x) < 0.03f && kotlin.math.abs(prev.y - bp.y) < 0.03f
            ) {
                barSeen = null
                if (barDebug < 3) { saveDebug(bmp, "barra_ok"); barDebug++ }
                say("Minigame: barra"); mash(g, w, h, bp); evBlockUntil = now() + 1500; return
            } else {
                barSeen = bp; barSeenAt = now()
            }
        }

        val th = cfg.powerPct / 100.0
        val v3 = snap.slotV(3); val v2 = snap.slotV(2); val v1 = snap.slotV(1)
        val p3 = snap.slotPink(3); val p2 = snap.slotPink(2); val p1 = snap.slotPink(1)
        val pinkTh = 0.04
        if (now() - lastInfo > 1000) {
            say("Combate | lutas: $fights | especial há ${(now() - lastPower2) / 1000}s")
            lastInfo = now()
        }
        val x3 = snap.slotBlocked(3); val x2 = snap.slotBlocked(2); val x1 = snap.slotBlocked(1)   // X vermelho = poder indisponível
        val c3 = !x3 && (p3 > pinkTh || v3 > th)
        val c2 = !x2 && (p2 > pinkTh || v2 > th)
        val c1 = !x1 && (p1 > pinkTh || v1 > th)
        // Toca no centro do número (círculo rosa) de cada slot.
        if (cfg.ult && c3 && usePower(3)) { markPower(); g.tap(w * 0.13f, h * 0.905f); lastEvCheck = 0; delay(120); return }
        if (c2 && usePower(2)) { markPower(); g.tap(w * 0.236f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }
        // Poder 2 com X vermelho (indisponível): usa o poder 1 se estiver carregado, em vez de ficar tocando no 2.
        if (x2 && c1 && usePower(1)) { markPower(); g.tap(w * 0.343f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }
        val sec = cfg.power1AfterSec
        if (sec > 0 && now() - lastPower2 > sec * 1000L && c1 && usePower(1)) {
            markPower(); g.tap(w * 0.343f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return
        }
        combo(g, w, h)
    }

    /** Há um mini-evento já lido e ainda fresco? Usa os mesmos gatilhos do combatTick. O combo usa isto para sair cedo. */
    private fun eventReady(): Boolean {
        val ev = evScreen ?: return false
        if (now() - evAt >= 1500 || now() < evBlockUntil) return false
        return miniOf(ev) != null
    }

    /**
     * ÚNICO lugar que decide qual minigame é, pelo texto do canto (a ordem importa):
     *  1) PING PONG (texto ARREMESSAR do jogo; seta que vai e volta) vem antes de tudo, para nunca virar o de arrastar;
     *  2) PASSE O DEDO (seta de arrastar);
     *  3) TOQUE RÁPIDO (bolinha ou barra: quem separa os dois é a FORMA, em touchGame);
     *  4) palavras soltas de "encher a barra", só na janela logo depois de usar um poder (senão dão falso positivo).
     * Os textos específicos valem sempre, porque também podem vir por um ícone na tela.
     */
    private fun miniOf(ev: Screen): Mini? = when {
        ev.has("ARREMESS") -> Mini.PING_PONG   // o jogo mostra "TOQUE PARA ARREMESSAR"; aqui o minigame se chama PING PONG
        ev.has("PASSE") || ev.has("DEDO") -> Mini.SETA
        ev.has("TOQUE") && ev.has("RAPIDO") -> Mini.TOQUE
        // Palavras soltas davam falso positivo no meio da luta: agora exige ENCHER e BARRA juntas. A barra também é achada pela
        // FORMA (confirmada em combatTick), então o texto genérico não é necessário.
        cfg.barGame && now() < evWindowUntil && ev.has("ENCHER") && ev.has("BARRA") -> Mini.BARRA
        else -> null
    }

    /** Intervalo entre leituras do texto do evento: rápido logo depois de usar um poder (minigame provável), lento no resto. */
    private fun evGap(): Long = if (now() < evWindowUntil) 90L else 300L

    /** Lê o texto do evento em segundo plano (no máximo uma leitura por vez, a cada ~0,15 s). */
    private fun scanEvent(bmp: Bitmap) {
        if (evBusy || now() - lastEvCheck < evGap()) return
        evBusy = true
        lastEvCheck = now()
        scope.launch {
            try {
                evScreen = text.read(bmp, 0.50f, 0.15f, 0.95f, 0.33f)
                evAt = now()
            } finally {
                evBusy = false
            }
        }
    }

    /**
     * Combo em spam. Entre os arrastos confere (barato) se o poder 2 carregou e, se sim, usa na hora:
     * antes o bot só olhava o poder depois de uma fila inteira de arrastos.
     */
    private suspend fun combo(g: BotAccessibilityService, w: Int, h: Int) {
        val th = cfg.powerPct / 100.0
        repeat(cfg.comboSwipes) { i ->
            val y = h * (0.38f + Random.nextFloat() * 0.27f)
            val x1 = w * (0.56f + Random.nextFloat() * 0.08f)
            val x2 = x1 + w * (0.14f + Random.nextFloat() * 0.06f)   // máx ≈ 0,84: longe dos botões do Android
            g.swipe(x1, y, x2, y, 40L + Random.nextInt(20))
            if (cfg.comboGapMs > 0) delay(cfg.comboGapMs.toLong())
            // Continua lendo o texto do evento DURANTE o combo e sai na hora se apareceu um: antes só olhava no começo de cada
            // rodada (8 arrastos, ~1 s), então o spam do minigame começava quase 1 s atrasado.
            if (!evBusy && now() - lastEvCheck >= evGap()) cap.latest()?.let { scanEvent(it) }
            if (eventReady()) return
            if (i % 2 == 1) {
                val b = cap.latest()
                if (b != null) {
                    val c = Heur.power(b, th, 0.04)
                    if (cfg.ult && c[0] && usePower(3)) { markPower(); g.tap(w * 0.13f, h * 0.905f); lastEvCheck = 0; delay(120); return }
                    if (c[1] && usePower(2)) { markPower(); g.tap(w * 0.236f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }
                    if (c[4] && c[2] && usePower(1)) { markPower(); g.tap(w * 0.343f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }   // poder 2 com X: usa o 1
                }
            }
        }
        if (cfg.block) g.block(w, h, 350)
    }

    /**
     * TOQUE RÁPIDO: acha o círculo e faz rajadas de toques no centro dele. O texto do evento some logo (nos prints ele já
     * não aparece com a bola ainda na tela), então o fim é decidido pela BOLA: some por ~5 leituras seguidas = acabou.
     */
    private suspend fun ball(g: BotAccessibilityService, w: Int, h: Int) {
        val t0 = now()
        val end = t0 + 9000
        var lastPt: PointF? = null
        var misses = 0
        var seen = false
        while (currentCoroutineContext().isActive && now() < end) {
            val bmp = cap.latest() ?: break
            val snap = Heur.snap(bmp)
            val p = try { snap.ball() } finally { snap.release() }
            if (p != null) { lastPt = p; misses = 0; seen = true } else misses++
            if (seen && misses >= 5) break
            if (!seen && now() - t0 > 2500) break
            val t = lastPt
            if (t == null) { delay(30); continue }
            // Bola achada: rajada de 6 toques (~150 ms) até a próxima detecção, porque ela se mexe. Sem achar: só 2, no último ponto.
            g.tapBurst(t.x * w, t.y * h, if (p != null) 6 else 2, 25L, 10L)
        }
    }

    /**
     * PASSE O DEDO RÁPIDO: a cada quadro acha a seta, pega o CENTRO dela e arrasta ao longo da seta passando
     * por esse centro. Repete enquanto houver seta (pode vir mais de uma, em direções diferentes). O texto do evento
     * some logo (no print já não aparece com a seta na tela), então o fim é decidido pela SETA: some por ~0,7 s = acabou.
     * Devolve true se achou a seta ao menos uma vez (minigame de verdade), false se foi alarme falso do texto.
     */
    private suspend fun swipeEvent(g: BotAccessibilityService): Boolean {
        val t0 = now()
        val end = t0 + 8000
        var misses = 0
        var did = 0
        var seen = false
        while (currentCoroutineContext().isActive && now() < end) {
            val bmp = cap.latest() ?: break
            val a = Heur.arrowFast(bmp)   // recorta antes de converter: bem mais leve que o quadro todo
            // A seta do ARREMESSAR (que vai e volta) também é azul e comprida: sem esta checagem o bot continuava arrastando
            // nela depois que o minigame trocou. Se o texto de ARREMESSAR apareceu, sai e deixa o combate tratar.
            scanEvent(bmp)
            val evx = evScreen
            if (evx != null && evAt > t0 + 200 && evx.has("ARREMESS")) { chainEv = true; break }
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            if (a == null) {
                misses++
                if (misses == 2 && did == 0) {   // não achou a seta: tenta "para baixo" no meio, como no seu print
                    if (arrowDebug < 3) { saveDebug(bmp, "seta_nao_achou"); arrowDebug++ }
                    g.swipe(w * 0.584f, h * 0.22f, w * 0.584f, h * 0.78f, 80)
                    did++
                }
                if (did <= 1 && misses >= 12) break   // nunca achou seta de verdade (leitura mais rápida: mais tentativas na mesma janela)
                if (misses >= 20) break               // já vinha achando e ela sumiu
                delay(20)
                continue
            }
            misses = 0
            seen = true
            if (arrowDebug < 3) { saveDebug(bmp, "seta_ok"); arrowDebug++ }
            val cx = a.cx * w
            val cy = a.cy * h
            val lenPx = if (a.dx != 0) a.len * w else a.len * h
            val half = (lenPx * 0.6f).coerceIn(h * 0.12f, h * 0.45f)
            // 3 arrastos seguidos e rápidos na mesma seta (sem pausa) antes de ler o quadro de novo.
            repeat(3) {
                g.swipe(
                    (cx - a.dx * half).coerceIn(0f, w - 1), (cy - a.dy * half).coerceIn(0f, h - 1),
                    (cx + a.dx * half).coerceIn(0f, w - 1), (cy + a.dy * half).coerceIn(0f, h - 1),
                    55,
                )
            }
            did += 2
        }
        return seen
    }

    /**
     * Minigame de encher a barra: toques em rajada NO CÍRCULO (ponto achado pela forma; se não achar, onde ele fica no print:
     * ~57% da largura, ~62% da altura) até o círculo sumir (~0,75 s). O texto do evento não serve de critério: some logo.
     * Se a forma nunca for achada (só o texto disparou), toca por 4 s no ponto padrão.
     */
    private suspend fun mash(g: BotAccessibilityService, w: Int, h: Int, start: PointF?) {
        val t0 = now()
        val end = t0 + 9000
        var pt = start ?: PointF(0.568f, 0.62f)
        var seen = start != null
        var misses = 0
        var lastCheck = now()   // o 1º toque sai já; a conferência da forma só vem ~0,15 s depois (antes ela atrasava o 1º toque)
        while (currentCoroutineContext().isActive && now() < end) {
            if (now() - lastCheck >= 150) {
                lastCheck = now()
                val b = cap.latest()
                if (b != null) {
                    val s = Heur.snap(b)
                    val p = try { s.barCircle() } finally { s.release() }
                    if (p != null) { pt = p; misses = 0; seen = true } else misses++
                }
                val el = now() - t0
                if (el > 1000 && ((seen && misses >= 5) || (!seen && el > 4000))) break
            }
            g.tapBurst(pt.x * w, pt.y * h, 6, 25L, 8L)
        }
    }

    /**
     * PING PONG (texto do jogo: "TOQUE PARA ARREMESSAR"): UM toque só, na hora em que o marcador passa pelo quadrado branco do centro
     * da barra (ampulheta). Em vez de um atraso fixo, o bot ACOMPANHA o marcador:
     *  1) A cada quadro lê a faixa da barra e guarda, por linha, o menor brilho já visto (o fundo parado). O marcador é o que aparece
     *     de brilhante por cima desse fundo (Heur.pingProfile + pingMarker). Funciona para qualquer seta/linha clara que se mova.
     *  2) Com 3 posições seguidas na mesma direção tira a velocidade e calcula quanto falta para chegar ao centro.
     *  3) Se ele vai PARA o centro e falta pouco, espera o resto (menos `pingLeadMs`, que cobre a captura e o atraso do toque) e toca.
     *     Se está se afastando, espera ele voltar (bate e volta). Se nunca achar o marcador (2,5 s), toca uma vez mesmo assim.
     * Nas 2 primeiras vezes grava recortes da barra e um log em debug/ (pp*), para ajustar a medida e a antecedência.
     */
    private suspend fun pingPong(g: BotAccessibilityService, w: Int, h: Int) {
        val k = tossDebug
        if (k < 2) tossDebug++
        val rec = k < 2
        val n = 100
        val bg = FloatArray(n) { 1f }
        val ts = ArrayList<Long>()
        val ys = ArrayList<Float>()
        val log = StringBuilder()
        val t0 = now()
        val lead = cfg.pingLeadMs
        var lastBmp: Bitmap? = null
        var frames = 0
        var saved = 0
        var tapped = false
        var why = ""
        while (currentCoroutineContext().isActive && now() - t0 < 7000) {
            val b = cap.latest()
            if (b == null) { delay(5); continue }
            if (b === lastBmp) { delay(3); continue }   // quadro repetido (a tela não mudou): não conta como nova leitura
            lastBmp = b
            val tf = now()
            val prof = Heur.pingProfile(b, n)
            val y = if (frames >= 1) pingMarker(prof, bg) else null
            for (i in 0 until n) if (prof[i] < bg[i]) bg[i] = prof[i]
            frames++
            if (y != null) { ts.add(tf); ys.add(y) }
            if (rec) {
                log.append("${tf - t0}ms y=${if (y != null) "%.3f".format(y) else "-"}\n")
                if (saved < 40 && frames % 2 == 0) { savePingCrop(b, "pp${k}_%02d_${tf - t0}ms".format(saved)); saved++ }
            }
            val m = ys.size
            if (m >= 3) {
                val ta = ts[m - 3]; val tc = ts[m - 1]
                val ya = ys[m - 3]; val yb = ys[m - 2]; val yc = ys[m - 1]
                val span = tc - ta
                if ((yb - ya) * (yc - yb) > 0f && span in 1L..300L) {   // mesma direção nos 3 pontos e pontos próximos no tempo
                    val v = (yc - ya) / (span / 1000f)               // proporção da altura por segundo
                    val toward = (Heur.PING_CENTER_Y - yc) * v > 0f
                    if (toward && kotlin.math.abs(v) >= 0.05f) {
                        val remaining = (Heur.PING_CENTER_Y - yc) / v - (now() - tc) / 1000f - lead / 1000f
                        if (rec) log.append("  v=%.3f falta=%.3f s\n".format(v, remaining))
                        if (remaining <= 0.12f) {
                            if (remaining > 0f) delay((remaining * 1000f).toLong())
                            g.tap(w * 0.565f, h * 0.48f)
                            tapped = true
                            why = "v=%.2f".format(v)
                            break
                        }
                    }
                }
            }
            if (ys.isEmpty() && now() - t0 > 2500) { why = "sem marcador"; break }
        }
        if (!tapped) {
            g.tap(w * 0.565f, h * 0.48f)   // último recurso: um toque (não achou o marcador ou não alinhou a tempo)
            if (why.isEmpty()) why = "tempo esgotado"
        }
        if (rec) {
            lastBmp?.let { savePingCrop(it, "pp${k}_TAP") }
            log.append("TOQUE aos ${now() - t0}ms (${why}), antecipação ${lead}ms\n")
            runCatching { File(debugDir, "pingpong${k}_log_$t0.txt").writeText(log.toString()) }
        }
        say("Ping pong: toque ($why)")
        delay(1200)
    }

    /** Posição (proporção da altura da tela) da maior mancha que ficou mais brilhante que o fundo; null se não há marcador. */
    private fun pingMarker(prof: FloatArray, bg: FloatArray): Float? {
        var bestMass = 0f
        var bestY = 0f
        var i = 0
        while (i < prof.size) {
            if (prof[i] - bg[i] >= 0.15f) {
                var j = i
                var mass = 0f
                var wy = 0f
                while (j < prof.size && prof[j] - bg[j] >= 0.15f) { val d = prof[j] - bg[j]; mass += d; wy += d * (j + 0.5f); j++ }
                if (mass > bestMass) { bestMass = mass; bestY = wy / mass }
                i = j
            } else i++
        }
        if (bestMass < 0.35f) return null
        return Heur.PING_Y0 + bestY / prof.size * (Heur.PING_Y1 - Heur.PING_Y0)
    }

    /** Grava só a faixa da barra (pequena e rápida) para a gente conferir o movimento depois. */
    private fun savePingCrop(b: Bitmap, tag: String) {
        runCatching {
            val c = Bitmap.createBitmap(b, (0.50f * b.width).toInt(), (0.22f * b.height).toInt(), (0.16f * b.width).toInt(), (0.56f * b.height).toInt())
            saveDebug(c, tag, 70)
            c.recycle()
        }
    }

    /**
     * Os minigames da BOLINHA e da BARRA podem aparecer com texto parecido (os dois são de tocar rápido), então o texto não
     * decide qual é. Quem decide é a FORMA, lida no quadro: círculo com a barra vertical em cima = BARRA; círculo solto (que
     * não é o da barra) = BOLINHA. Espera até 2,5 s a forma aparecer. Se nada aparecer e o gatilho foi genérico, toca no ponto da barra.
     */
    private suspend fun touchGame(g: BotAccessibilityService, w: Int, h: Int, preferBar: Boolean): Boolean {
        val t0 = now()
        val waitMs = if (preferBar) 600L else 1500L   // BARRA por texto: espera pouco (a forma também é vigiada em combatTick)
        while (currentCoroutineContext().isActive && now() - t0 < waitMs) {   // 1,5 s: espera mais que isso só atrasava o combo em falso alarme
            val b = cap.latest()
            if (b != null) {
                val s = Heur.snap(b)
                var bar: PointF? = null
                var ballPt: PointF? = null
                try {
                    bar = s.barCircle()
                    if (bar == null) ballPt = s.ball()   // ball() já ignora o círculo da barra
                } finally { s.release() }
                if (bar != null && cfg.barGame) { say("Minigame: barra"); mash(g, w, h, bar); return true }
                if (ballPt != null) { say("Minigame: bolinha"); ball(g, w, h); return true }
            }
            delay(40)
        }
        // Antes, se só o texto disparou e a forma não apareceu, o bot tocava 4 s às cegas no ponto padrão (travava o combo em falso
        // alarme). Agora sem forma = não é minigame: não toca em nada.
        return false   // forma não achada: não conta como minigame confirmado (o chamador bloqueia o gatilho por um tempo)
    }

    private fun saveDebug(b: Bitmap, tag: String, quality: Int = 85) {
        runCatching {
            File(debugDir, "${tag}_${now()}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        }
    }
}
