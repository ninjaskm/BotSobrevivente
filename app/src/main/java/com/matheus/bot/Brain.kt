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

private enum class Sc { COMBATE, AVANCAR, CONTINUAR, FECHAR, CONFIRMA, LOBBY_MORTO, LOBBY_VIVO, ROLETA, HOME, EQUIPE_VAZIA, DESCONHECIDA }

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
    private var lastBlindSpin = 0L
    private var lastProbe = 0L
    private var noCombat = 0
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
    private var barDebug = 0

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
                if (err == null) say("Time 1 escolhido. Equipe os 3 na mão (equipamentos = próxima etapa) e aperte ▶ Play. Pausado.")
                else say("$err. Pausado.")
                paused = true
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
                    if (top.hasAny("GIRE", "RODA", "SORTEAR", "RECOMPENSAS")) {
                        spinWheel(bmp, g)
                    } else {
                        enter(Sc.DESCONHECIDA)
                        unknown++
                        if (unknown == 1) {
                            saveDebug(bmp, "desconhecida")
                            say("Tela desconhecida")
                        }
                        // Tentativa às cegas: se ficou parado numa tela sem texto reconhecido, pode ser a roleta.
                        if (unknown >= 4 && now() - lastBlindSpin > 4000) {
                            say("Tela desconhecida: tentando girar a roleta")
                            lastBlindSpin = now()
                            saveDebug(bmp, "cega")
                            spinSwipe(bmp, g)
                        }
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
        val ok = spinSwipe(bmp, g)
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
            val handled = when {
                ev.has("PASSE") || ev.has("DEDO") -> { say("Minigame: seta"); swipeEvent(g); true }
                ev.has("ARREMESS") -> { say("Minigame: arremesso"); toss(g, w, h); true }
                ev.has("TOQUE") && ev.has("RAPIDO") -> { say("Minigame: bolinha"); ball(g, w, h); true }
                // Minigame de encher a barra (texto ainda não visto): spam de toques na tela enquanto o texto durar.
                // Os gatilhos genéricos (palavras soltas) só valem na janela depois de usar um poder; os textos específicos
                // acima (PASSE O DEDO, ARREMESSAR, TOQUE RÁPIDO) valem sempre, porque também podem vir por um ícone na tela.
                now() < evWindowUntil && ev.hasAny("ENCHER", "BARRA", "CARREG", "MARTEL", "TOQUE") -> {
                    if (evDebug < 3) { saveDebug(bmp, "evento_barra"); evDebug++ }
                    say("Minigame: barra"); mash(g, w, h, null); true
                }
                else -> false
            }
            if (handled) { evBlockUntil = now() + 800; return }
        }

        // Gatilho pela FORMA (só a barra de encher), para quando o texto do evento já sumiu ou o OCR não leu: círculo pequeno
        // + barra vertical. A cada ~0,3 s. A seta do PASSE O DEDO NÃO dispara por forma: brilhos azuis do cenário e dos
        // personagens (ex.: Batman) eram lidos como seta e interrompiam o combo; ela só começa pelo TEXTO do evento.
        if (now() >= evBlockUntil && now() < evWindowUntil && now() - lastShape > 300) {
            lastShape = now()
            val bp = snap.barCircle()
            if (bp != null) {
                if (barDebug < 3) { saveDebug(bmp, "barra_ok"); barDebug++ }
                say("Minigame: barra"); mash(g, w, h, bp); evBlockUntil = now() + 500; return
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
        if (cfg.ult && c3) { markPower(); g.tap(w * 0.13f, h * 0.905f); lastEvCheck = 0; delay(120); return }
        if (c2) { markPower(); g.tap(w * 0.236f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }
        // Poder 2 com X vermelho (indisponível): usa o poder 1 se estiver carregado, em vez de ficar tocando no 2.
        if (x2 && c1) { markPower(); g.tap(w * 0.343f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }
        val sec = cfg.power1AfterSec
        if (sec > 0 && now() - lastPower2 > sec * 1000L && c1) {
            markPower(); g.tap(w * 0.343f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return
        }
        combo(g, w, h)
    }

    /** Há um mini-evento já lido e ainda fresco? Usa os mesmos gatilhos do combatTick. O combo usa isto para sair cedo. */
    private fun eventReady(): Boolean {
        val ev = evScreen ?: return false
        if (now() - evAt >= 1500 || now() < evBlockUntil) return false
        return ev.has("PASSE") || ev.has("DEDO") || ev.has("ARREMESS") || (ev.has("TOQUE") && ev.has("RAPIDO")) ||
            (now() < evWindowUntil && ev.hasAny("ENCHER", "BARRA", "CARREG", "MARTEL", "TOQUE"))
    }

    /** Lê o texto do evento em segundo plano (no máximo uma leitura por vez, a cada ~0,15 s). */
    private fun scanEvent(bmp: Bitmap) {
        if (evBusy || now() - lastEvCheck < 150) return
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
            if (!evBusy && now() - lastEvCheck >= 150) cap.latest()?.let { scanEvent(it) }
            if (eventReady()) return
            if (i % 2 == 1) {
                val b = cap.latest()
                if (b != null) {
                    val c = Heur.power(b, th, 0.04)
                    if (cfg.ult && c[0]) { markPower(); g.tap(w * 0.13f, h * 0.905f); lastEvCheck = 0; delay(120); return }
                    if (c[1]) { markPower(); g.tap(w * 0.236f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }
                    if (c[4] && c[2]) { markPower(); g.tap(w * 0.343f, h * 0.905f); lastPower2 = now(); lastEvCheck = 0; delay(120); return }   // poder 2 com X: usa o 1
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
     */
    private suspend fun swipeEvent(g: BotAccessibilityService) {
        val t0 = now()
        val end = t0 + 8000
        var misses = 0
        var did = 0
        while (currentCoroutineContext().isActive && now() < end) {
            val bmp = cap.latest() ?: break
            val snap = Heur.snap(bmp)
            val a = try { snap.arrow() } finally { snap.release() }
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            if (a == null) {
                misses++
                if (misses == 2 && did == 0) {   // não achou a seta: tenta "para baixo" no meio, como no seu print
                    if (arrowDebug < 3) { saveDebug(bmp, "seta_nao_achou"); arrowDebug++ }
                    g.swipe(w * 0.584f, h * 0.22f, w * 0.584f, h * 0.78f, 80)
                    did++
                }
                if (did <= 1 && misses >= 8) break   // nunca achou seta de verdade
                if (misses >= 14) break               // já vinha achando e ela sumiu
                delay(40)
                continue
            }
            misses = 0
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
     * TOQUE PARA ARREMESSAR: o marcador ainda não foi mapeado (o print mostra uma barra vertical em forma de ampulheta
     * com uma mira no centro; parece ser preciso tocar quando algo chega ao centro da mira).
     * Por ora toca depois de um atraso configurável, na mira, e grava ~2,4 s de quadros (24 quadros) nas 2 primeiras vezes,
     * em segundo plano, para a gente ver o movimento e acertar o momento.
     */
    private suspend fun toss(g: BotAccessibilityService, w: Int, h: Int) {
        val k = tossDebug
        if (k < 2) {
            tossDebug++
            scope.launch {
                repeat(24) { n ->
                    cap.latest()?.let { saveDebug(it, "arremesso${k}_%02d".format(n), 60) }
                    delay(100)
                }
            }
        }
        delay(cfg.tossDelayMs.toLong())
        g.tap(w * 0.565f, h * 0.48f)
        delay(1200)
    }

    private fun saveDebug(b: Bitmap, tag: String, quality: Int = 85) {
        runCatching {
            File(debugDir, "${tag}_${now()}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        }
    }
}
