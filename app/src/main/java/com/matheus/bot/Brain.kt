package com.matheus.bot

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
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
        set(v) { if (pausedFlag != v) { pausedFlag = v; if (v) Session.inc("pausas"); onPausedChange(v) } }
    val isPaused: Boolean get() = pausedFlag
    private var job: Job? = null
    private var fights = 0
    private var unknown = 0
    private var deadVotes = 0
    @Volatile private var last: Sc? = null
    private var stopAfter = false
    private var lastCenterTap = 0L
    private val powerPolicy = PowerPolicy(cfg)
    private var lastInfo = 0L
    @Volatile private var lastEvCheck = 0L
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
    /** Vigia de inatividade: marca de referência (início/última ação do vigia) e quantas tentativas seguidas sem reconhecer a tela. */
    private var idleMark = 0L
    private var stuckN = 0
    private val IDLE_LIMIT_MS = 8000L
    private var transientErrors = 0
    private var lastRecoveryNotice = 0L

    /**
     * PROTEÇÃO DE MONTAGEM DE TIMES. Liga (no máximo 90 s) quando o bot clica em SOBREVIVENTE e quando o time 1 morre, e desliga quando
     * a montagem termina ou quando aparece o lobby com LUTAR / combate. Enquanto ligada o Brain NÃO gira a roleta (o arrasto rápido para
     * a esquerda no meio da tela, que lança a lista de personagens) e ignora a evidência fraca de combate.
     */
    @Volatile private var teamGuardUntil = 0L
    private val guarded: Boolean get() = now() < teamGuardUntil
    private fun guardOn() { teamGuardUntil = now() + 90_000L }
    private fun guardOff() { teamGuardUntil = 0L }
    private var spinDebug = 0
    private val swap = TeamSwap(cap, text, cfg, { paused }) { b, tag -> saveDebug(b, tag) }.also { it.say = say; it.artLog = { l -> artLog(l) } }
    private var lastHud = 0.0
    private var lastPur = 0.0
    private val evBusy = AtomicBoolean(false)
    @Volatile private var evScreen: Screen? = null
    @Volatile private var evAt = 0L
    @Volatile private var evBlockUntil = 0L
    /** Quadros anteriores ao último especial não podem disparar nem sobrescrever a leitura do minigame atual. */
    @Volatile private var evAfter = 0L
    private var lastShape = 0L
    /** Janela em que um minigame pode aparecer: logo depois de o bot usar um poder (1, 2 ou 3). */
    @Volatile private var evWindowUntil = 0L
    private fun markPower() { evWindowUntil = now() + 8000 }

    private var barDebug = 0
    /** Última detecção da forma da barra (ainda não confirmada) e quando foi. Só vira minigame se aparecer 2x seguidas no mesmo lugar. */
    private var barSeen: PointF? = null
    private var barSeenAt = 0L

    // ---- Vigia (percepção em paralelo): campos ----
    /** Última leitura rápida dos poderes feita pelo vigia, no formato de Heur.power: [0..2] carregado (slot 3, 2, 1) e [3..5] X vermelho. `ts` = horário da captura do quadro (ms). */
    private class Perceived(val ts: Long, val power: BooleanArray)
    @Volatile private var perceived: Perceived? = null
    private var watcherJob: Job? = null
    /** true enquanto um minigame lê os quadros por conta própria: o vigia não disputa a CPU (só grava, se o modo de coleta estiver ligado). */
    @Volatile private var watcherHold = false
    /** Nome do minigame em andamento (só para o .json da coleta). */
    @Volatile private var miniNow = ""
    /** Forma da barra de encher já confirmada pelo vigia (2 leituras seguidas no mesmo ponto) e quando. O combatTick consome. */
    @Volatile private var barConfirmed: PointF? = null
    @Volatile private var barConfirmedAt = 0L
    private var lastRec = 0L
    private val recN = AtomicInteger(0)
    private var lastTrim = 0L

    fun start() {
        paused = false
        idleMark = now(); stuckN = 0
        if (job?.isActive != true) {
            fights = 0; unknown = 0; deadVotes = 0; last = null; stopAfter = false
            job = scope.launch { loop() }
        }
        startWatcher()
        say("Rodando")
    }

    fun pause() { paused = true; say("Pausado") }
    fun stop() { job?.cancel(); watcherJob?.cancel(); Session.flush(debugDir, force = true) }

    private fun now() = System.currentTimeMillis()

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                Perf.flush(debugDir)
                trimMaybe()
                Session.flush(debugDir)
                if (paused) { delay(200); continue }
                val g = BotAccessibilityService.instance
                if (g == null) {
                    if (now() - lastRecoveryNotice > 5000L) {
                        say("Aguardando o serviço de acessibilidade; vou tentar de novo.")
                        lastRecoveryNotice = now()
                    }
                    delay(1000)
                    continue
                }
                val bmp = cap.latest()
                if (bmp == null) { delay(100); continue }
                val snap = Perf.time("snap") { Heur.snap(bmp) }
                try {
                    lastHud = snap.hud(); lastPur = snap.purple()
                    // VIGIA DE INATIVIDADE (regra geral, vale para qualquer tela): 8 s sem nenhum gesto = o ciclo travou. Relê a tela do zero
                    // e tenta seguir. Fora da montagem de times (que tem o próprio limite de 90 s e faz gestos o tempo todo).
                    if (!guarded && now() - maxOf(BotAccessibilityService.lastGestureAt, idleMark) > IDLE_LIMIT_MS) {
                        unstick(bmp, g, snap)
                        continue
                    }
                    val strict = snap.isCombat()
                    // Evidência fraca de luta também vale: já em combate (sinal folgado) ou numa "tela desconhecida" com os
                    // slots roxos de poder bem visíveis (era o caso do bot ficar preso em "desconhecida" no meio da luta).
                    val fightish = strict || (!guarded && ((last == Sc.COMBATE && snap.isCombatLoose()) ||
                        (last == Sc.DESCONHECIDA && lastPur > 0.25)))
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
                transientErrors = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transientErrors++
                if (transientErrors == 1 || transientErrors % 5 == 0) {
                    android.util.Log.e("Brain", "Falha no ciclo principal; tentando recuperar (tentativa $transientErrors)", e)
                    cap.latest()?.let { saveDebug(it, "erro_ciclo_$transientErrors") }
                    say("Erro ${e.javaClass.simpleName}; tentando recuperar (tentativa $transientErrors).")
                }
                // Descarta só o estado visual transitório. A luta, a equipe e o pedido manual de pausa são preservados.
                last = null
                deadVotes = 0
                noCombat = 0
                unknown = 0
                idleMark = now()
                delay((250L * transientErrors.coerceAtMost(20)).coerceAtMost(5000L))
            }
        }
    }

    private var combatStart = 0L

    /**
     * Chamado quando ficou [IDLE_LIMIT_MS] sem gesto. Esquece o estado (tela anterior, votos, bloqueios) e decide de novo pela tela
     * atual: combate ou menu completo. Se mesmo assim não agiu, escala: (1) botão genérico de seguir (CONTINUAR, AVANÇAR, FECHAR,
     * ACEITAR, COLETAR, RESGATAR) em qualquer ponto da metade de baixo; (2) toque no centro da tela, no máximo 5 vezes seguidas.
     * Não toca em RECEBER (no lobby seria PARAR E RECEBER e encerraria o ciclo) nem em LUTAR (respeita a meta de lutas).
     */
    private suspend fun unstick(bmp: Bitmap, g: BotAccessibilityService, snap: Snap) {
        idleMark = now()
        stuckN++
        Session.inc("watchdog")
        if (stuckN <= 3) saveDebug(bmp, "parado_8s")
        say("Parado há 8s. Relendo a tela (tentativa $stuckN)...")
        last = null; deadVotes = 0; unknown = 0; detailTaps = 0
        evScreen = null; evBlockUntil = 0L; chainEv = false; barConfirmed = null; barSeen = null
        val before = BotAccessibilityService.lastGestureAt
        if (snap.isCombat()) combatTick(bmp, snap, g) else menuTick(bmp, g)
        if (BotAccessibilityService.lastGestureAt > before || paused) return
        val s = text.read(bmp)
        val btn = listOf("CONTINUAR", "AVANCAR", "FECHAR", "ACEITAR", "COLETAR", "RESGATAR")
            .firstNotNullOfOrNull { s.find(it, yMin = 0.4f) }
        if (btn != null) { say("Parado: tocando em ${btn.text}"); tapW(g, btn, bmp); delay(800); return }
        if (stuckN in 2..6) { say("Parado: toque no centro da tela"); tapPoint(g, bmp, 0.5f, 0.5f); delay(800) }
    }

    private fun enter(sc: Sc) {
        if (sc == last) return
        if (last == Sc.COMBATE && combatStart > 0) { Session.add("combate_ms", now() - combatStart); combatStart = 0L }
        if (sc == Sc.AVANCAR) { fights++; Session.inc("lutas") }
        if (sc == Sc.COMBATE) { powerPolicy.resetTimer(now()); combatStart = now() }
        if (sc == Sc.LOBBY_VIVO || sc == Sc.COMBATE || sc == Sc.AVANCAR || sc == Sc.CONTINUAR || sc == Sc.CONFIRMA || sc == Sc.ROLETA) guardOff()
        unknown = 0
        if (sc != Sc.DESCONHECIDA) stuckN = 0
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
        // O botão grande do lobby se chama LUTAR e, depois da troca de time (ou de uma run repetida), REPETIR: mesmo lugar, mesma função.
        val lutar = s.find("LUTAR", 0.45f, 0.78f, 0.30f, 0.65f) ?: s.find("REPETIR", 0.45f, 0.78f, 0.30f, 0.65f)
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
                    say("Tela de detalhes não fechou; vou tentar novamente.")
                    detailTaps = 0
                    delay(1000)
                } else {
                    tapPoint(g, bmp, 0.16f, 0.40f)
                    delay(900)
                }
            }
            receber != null && equipe != null -> lobby(bmp, g, lutar, receber)
            !guarded && receber == null && (s.has("GIRE") || s.has("SORTEAR") || s.has("RODA", yMax = 0.35f) || s.has("RECOMPENSAS", yMax = 0.35f)) -> {
                spinWheel(bmp, g)
            }
            sobrev != null && s.hasAny("BATALHA", "COLECAO", "LOJA") -> {
                enter(Sc.HOME)
                if (stopAfter) { say("Ciclo terminado."); paused = true }
                else { guardOn(); tapW(g, sobrev, bmp); delay(1500) }
            }
            s.has("CRIE SUA EQUIPE") -> {
                enter(Sc.EQUIPE_VAZIA)
                say("Montando o time 1...")
                showUi(false)
                val err = try { guardOn(); delay(700); swap.createTeam1(g) } finally { showUi(true); guardOff() }
                if (err == null) say("Time 1 pronto (personagens e equipamentos). Seguindo.")
                else {
                    say("$err. Vou reler a tela e tentar continuar.")
                    last = null
                    deadVotes = 0
                    delay(1000)
                }
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
                    if (!guarded && receber == null && (top.hasAny("GIRE", "RODA", "SORTEAR", "RECOMPENSAS") ||
                        top.has("PARTIDA", yMax = 0.25f) || s.has("PARTIDA", yMax = 0.25f))) {
                        spinWheel(bmp, g)
                    } else {
                        enter(Sc.DESCONHECIDA)
                        unknown++
                        if (unknown == 1) {
                            saveDebug(bmp, "desconhecida")
                            Session.inc("tela_desconhecida")
                            say("Tela desconhecida")
                        }
                        // Tela desconhecida: NÃO gira a roleta às cegas. Só continua lendo (OCR geral + faixa de cima) a cada volta e age
                        // normalmente assim que reconhecer a tela. Se ficar tempo demais sem reconhecer, pausa (abaixo).
                        if (unknown > 120 && unknown % 120 == 0) {
                            saveDebug(bmp, "desconhecida_recuperacao")
                            say("Tela ainda desconhecida; tentando reconhecer de novo.")
                        }
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
        if (spinDebug < 5) { saveDebug(bmp, "roleta_disparou"); spinDebug++ }   // para conferir se a roleta disparou numa tela que não era a dela
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

    /**
     * O anel vermelho brilhante do botão grande (LUTAR/REPETIR) está na tela? Reserva para quando o leitor de texto não lê o nome do
     * botão: sem isto o bot achava que o time tinha morrido (no lobby de time morto não existe esse botão). Olha só a região do anel
     * (x 0,57–0,70, y 0,33–0,60), longe dos X vermelhos das cartas.
     */
    private fun ringVisible(bmp: Bitmap): Boolean {
        val x0 = (0.57f * bmp.width).toInt(); val x1 = (0.70f * bmp.width).toInt()
        val y0 = (0.33f * bmp.height).toInt(); val y1 = (0.60f * bmp.height).toInt()
        val hsv = FloatArray(3)
        var n = 0; var hit = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv)
                n++
                if ((hsv[0] < 12f || hsv[0] > 348f) && hsv[1] > 0.55f && hsv[2] > 0.75f) hit++
                x += 10
            }
            y += 10
        }
        return n > 0 && hit.toDouble() / n > 0.02
    }

    /**
     * O X VERMELHO no centro do botão grande? É o que aparece no lugar de LUTAR quando o time inteiro morreu (o botão fica com um X em vez
     * do texto). Valida a morte do time. Olha o miolo do botão (x 0,575–0,64, y 0,38–0,56): X = muito vermelho vivo e quase nenhum
     * branco; LUTAR/REPETIR tem letras brancas grandes ali. Diferente do anel, que é vermelho nos dois casos (por isso o anel sozinho
     * confundia o botão de X com o de lutar).
     */
    private fun deadX(bmp: Bitmap): Boolean {
        val x0 = (0.575f * bmp.width).toInt(); val x1 = (0.64f * bmp.width).toInt()
        val y0 = (0.38f * bmp.height).toInt(); val y1 = (0.56f * bmp.height).toInt()
        val hsv = FloatArray(3)
        var n = 0; var red = 0; var white = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv)
                n++
                if ((hsv[0] < 12f || hsv[0] > 348f) && hsv[1] > 0.6f && hsv[2] > 0.7f) red++
                if (hsv[1] < 0.25f && hsv[2] > 0.85f) white++
                x += 6
            }
            y += 6
        }
        return n > 0 && red.toDouble() / n > 0.06 && white.toDouble() / n < 0.02
    }

    /**
     * A CAVEIRA branca sobre as 3 cartas do time (a carta morta fica com caveira + X por cima). Segundo validador de "time morto",
     * independente do X do botão central. Para cada carta olha uma caixa no centro da carta (x 32–68% da largura da carta,
     * y 0,396–0,535 da tela): a caveira enche ~35% dela de branco, e as faixas logo acima e abaixo ficam sem branco (~0%). Só vale
     * se as TRÊS cartas têm a caveira: o time inteiro morreu. Calibrado no print do lobby com o time morto (brancoCaixa 0,34–0,36).
     * Cartas vivas mostram a arte do personagem, sem um bloco branco isolado nesse ponto.
     */
    private fun deadCards(bmp: Bitmap): Boolean {
        val cardX = floatArrayOf(0.069f, 0.227f, 0.386f)   // início de cada carta (proporção da largura)
        val cardW = 0.142f
        val hsv = FloatArray(3)
        fun whiteFrac(x0: Int, x1: Int, y0: Int, y1: Int): Double {
            var n = 0; var wh = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    Color.colorToHSV(bmp.getPixel(x, y), hsv)
                    n++
                    if (hsv[1] < 0.30f && hsv[2] > 0.80f) wh++
                    x += 4
                }
                y += 4
            }
            return if (n == 0) 0.0 else wh.toDouble() / n
        }
        for (cx in cardX) {
            val x0 = ((cx + 0.32f * cardW) * bmp.width).toInt()
            val x1 = ((cx + 0.68f * cardW) * bmp.width).toInt()
            val inside = whiteFrac(x0, x1, (0.396f * bmp.height).toInt(), (0.535f * bmp.height).toInt())
            val above = whiteFrac(x0, x1, (0.28f * bmp.height).toInt(), (0.375f * bmp.height).toInt())
            val below = whiteFrac(x0, x1, (0.549f * bmp.height).toInt(), (0.597f * bmp.height).toInt())
            if (inside < 0.20 || above > 0.05 || below > 0.05) return false
        }
        return true
    }

    private suspend fun lobby(bmp: Bitmap, g: BotAccessibilityService, lutarTxt: Word?, receber: Word) {
        // VALIDADORES DE TIME MORTO (dois, independentes): o X vermelho no botão central e a caveira+X nas 3 cartas.
        // Se o texto do botão não foi lido mas o anel vermelho está lá, o time está vivo: toca no centro do botão (x ~0,61 / y ~0,47).
        // EXCETO se algum validador mostra a morte: aí o anel é do botão de time morto, não de LUTAR.
        val xBtn = deadX(bmp)
        val xCards = deadCards(bmp)
        val xDead = xBtn || xCards
        // Os DOIS sinais juntos são prova forte: nesse caso um "LUTAR"/"REPETIR" lido pelo OCR é engano (ex.: texto de outra região).
        val strongDead = xBtn && xCards
        val lutar: Word? = if (strongDead) null
            else lutarTxt ?: if (!xDead && ringVisible(bmp)) Word("LUTAR", 0.61f, 0.47f, 0.2f, 0.2f) else null
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
            // Sem o botão LUTAR: confirma por 3 pontos antes de concluir "time morto". Cada validador (X do botão, caveira nas cartas)
            // vale 2; os dois juntos fecham na 1ª leitura (4 pontos). Sem nenhum, cada leitura vale 1 (precisa de 3 seguidas).
            if (xDead && deadVotes == 0) saveDebug(bmp, "time_morto_x")
            deadVotes += (if (xBtn) 2 else 0) + (if (xCards) 2 else 0)
            if (!xDead) deadVotes += 1
            if (deadVotes >= 3) {
                enter(Sc.LOBBY_MORTO)
                if (team == 1) {
                    Session.inc("time_morto")
                    say("Time 1 morreu. Trocando para o time 2...")
                    showUi(false)
                    var err: String? = "iniciando troca"
                    try {
                        while (err != null && !paused) {
                            guardOn()
                            err = try {
                                delay(700)
                                swap.run(g)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("Brain", "Falha durante a troca; tentando novamente", e)
                                "Erro ${e.javaClass.simpleName} durante a troca"
                            }
                            if (err != null && !paused) {
                                say("$err. Tentando continuar a troca...")
                                delay(1200)
                            }
                        }
                    } finally { showUi(true); guardOff() }
                    if (err == null) {
                        team = 2
                        deadVotes = 0
                        say("Time 2 escolhido. Seguindo a luta.")
                    } else if (paused) {
                        say("Troca interrompida pela pausa manual.")
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
        if (paused) return
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
                miniNow = kind.name
                watcherHold = true
                val acted: Boolean = try {
                    when (kind) {
                        Mini.PING_PONG -> { say("Minigame: ping pong"); pingPong(g, w, h); true }
                        Mini.SETA -> { say("Minigame: seta"); swipeEvent(g) }
                        Mini.TOQUE -> touchGame(g, w, h, preferBar = false)
                        Mini.BARRA -> {
                            if (evDebug < 3) { saveDebug(bmp, "evento_barra"); evDebug++ }
                            touchGame(g, w, h, preferBar = true)
                        }
                    }
                } finally {
                    watcherHold = false; miniNow = ""
                }
                if (paused) return
                Session.inc("mini_${kind.name}_" + if (acted) "ok" else "falso")
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
        // A deteccao da forma agora roda no vigia (watchBar), em paralelo; aqui so se consome a barra ja confirmada.
        val bc = barConfirmed
        if (bc != null && cfg.barGame && now() - barConfirmedAt < 800 && now() >= evBlockUntil) {
            barConfirmed = null
            if (barDebug < 3) { saveDebug(bmp, "barra_ok"); barDebug++ }
            say("Minigame: barra")
            miniNow = "BARRA"; watcherHold = true
            Session.inc("mini_BARRA_forma")
            try { mash(g, w, h, bc) } finally { watcherHold = false; miniNow = "" }
            evBlockUntil = now() + 1500; return
        }

        val th = cfg.powerPct / 100.0
        val v3 = snap.slotV(3); val v2 = snap.slotV(2); val v1 = snap.slotV(1)
        val p3 = snap.slotPink(3); val p2 = snap.slotPink(2); val p1 = snap.slotPink(1)
        val pinkTh = 0.04
        if (now() - lastInfo > 1000) {
            say("Combate | lutas: $fights | especial há ${(now() - powerPolicy.lastPower2At) / 1000}s")
            lastInfo = now()
        }
        val x3 = snap.slotBlocked(3); val x2 = snap.slotBlocked(2); val x1 = snap.slotBlocked(1)   // X vermelho = poder indisponível
        val c3 = !x3 && (p3 > pinkTh || v3 > th)
        val c2 = !x2 && (p2 > pinkTh || v2 > th)
        val c1 = !x1 && (p1 > pinkTh || v1 > th)
        // Política única: mesma prioridade e trava para a leitura local e para a percepção em paralelo.
        val powerRead = booleanArrayOf(c3, c2, c1, x3, x2, x1)
        if (useReadyPower(powerRead, g, w, h)) return
        combo(g, w, h)
    }

    private suspend fun useReadyPower(reading: BooleanArray, g: BotAccessibilityService, w: Int, h: Int): Boolean {
        if (paused) return false
        val slot = powerPolicy.select(reading, now()) ?: return false
        perceived = null
        markPower()
        // Invalida texto antigo antes do toque. evAfter será atualizado ao terminar o gesto para descartar também
        // OCRs que já estavam em andamento com quadros anteriores ao especial.
        evScreen = null
        evAt = 0L
        lastEvCheck = 0L
        g.tap(w * slot.xFraction, h * 0.905f)
        evAfter = now()
        evScreen = null
        evAt = 0L
        lastEvCheck = 0L
        powerPolicy.recordAction(slot, now())
        return true
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
    private fun evGap(): Long = if (now() < evWindowUntil) 10L else 300L

    /** Lê o texto do evento em segundo plano (no máximo uma leitura por vez, a cada ~0,15 s). */
    private fun scanEvent(bmp: Bitmap, capturedAt: Long = now()) {
        if (capturedAt < evAfter) return
        if (now() - lastEvCheck < evGap()) return
        if (!evBusy.compareAndSet(false, true)) return   // uma leitura por vez (o vigia, o combo e a seta chamam isto)
        lastEvCheck = now()
        scope.launch {
            try {
                val result = text.read(bmp, 0.50f, 0.15f, 0.95f, 0.33f)
                if (capturedAt >= evAfter) {
                    evScreen = result
                    evAt = capturedAt
                }
            } finally {
                evBusy.set(false)
            }
        }
    }

    /**
     * Combo em spam. Entre os arrastos confere (barato) se o poder 2 carregou e, se sim, usa na hora:
     * antes o bot só olhava o poder depois de uma fila inteira de arrastos.
     */
    private suspend fun combo(g: BotAccessibilityService, w: Int, h: Int) {
        val th = cfg.powerPct / 100.0
        val total = cfg.comboSwipes
        val batch = cfg.comboBatch.coerceIn(1, 4)
        var done = 0
        while (!paused && done < total) {
            val n = minOf(batch, total - done)
            val segs = ArrayList<SwipeSeg>(n)
            repeat(n) {
                val y = h * (0.38f + Random.nextFloat() * 0.27f)
                val x1 = w * (0.56f + Random.nextFloat() * 0.08f)
                val x2 = x1 + w * (0.14f + Random.nextFloat() * 0.06f)   // máx ≈ 0,84: longe dos botões do Android
                segs.add(SwipeSeg(x1, y, x2, y, 40L + Random.nextInt(20)))
            }
            val gapMs = if (cfg.comboGapMs > 0) {
                ceil(cfg.comboGapMs * 1.3).toLong().coerceAtLeast(cfg.comboGapMs + 1L)
            } else {
                ceil(segs.map { it.dur }.average() * 0.30).toLong().coerceAtLeast(2L)
            }
            // Lote: vários arrastos num único gesto (menos ida e volta ao sistema). Com lote 1 é o comportamento antigo.
            if (n == 1) {
                val s = segs[0]
                g.swipe(s.x1, s.y1, s.x2, s.y2, s.dur)
                delay(gapMs)
            } else {
                g.swipeBatch(segs, gapMs)
            }
            done += n
            // O vigia já lê o texto do evento, a barra e os poderes em paralelo. Aqui só se consulta o resultado (custo zero)
            // e sai na hora se apareceu um minigame ou um poder carregou. Se o vigia não estiver rodando, pede a leitura do texto.
            if (!evBusy.get() && now() - lastEvCheck >= evGap()) cap.latest()?.let { scanEvent(it) }
            if (eventReady() || barReady()) return
            val c = freshPower(th)
            if (c != null && useReadyPower(c, g, w, h)) return
        }
        if (cfg.block && !paused) g.block(w, h, 350)
        // Pausa entre combos, não um atraso cego: poder ou minigame pronto interrompe o intervalo.
        val nextComboAt = now() + 450L
        while (!paused && now() < nextComboAt) {
            if (eventReady() || barReady()) return
            val c = freshPower(th)
            if (c != null && useReadyPower(c, g, w, h)) return
            val remaining = nextComboAt - now()
            if (remaining > 0) delay(minOf(20L, remaining))
        }
    }

    /** A forma da barra já foi confirmada pelo vigia e ainda está fresca? */
    private fun barReady(): Boolean =
        cfg.barGame && barConfirmed != null && now() - barConfirmedAt < 800 && now() >= evBlockUntil

    /** Poderes: usa a leitura do vigia se tiver até 300 ms; senão lê o quadro mais recente. Formato de Heur.power. */
    private fun freshPower(th: Double): BooleanArray? {
        val p = perceived
        if (p != null && now() - p.ts < 300) return p.power
        val b = cap.latest() ?: return null
        return Perf.time("power") { Heur.power(b, th, 0.04) }
    }

    // ---------------- Vigia (percepção em paralelo) ----------------

    private fun startWatcher() {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch { watch() }
    }

    /** De tempos em tempos limpa a pasta debug/ (não mexe em perf*, pingpong_acertos* nem na subpasta dataset). */
    private fun trimMaybe() {
        if (now() - lastTrim < 300_000) return
        lastTrim = now()
        DebugStore.trim(debugDir)
    }

    /**
     * Vigia: enquanto o bot está em combate, lê CADA quadro novo (sem esperar o combo) só com recortes pequenos:
     *  - poderes (Heur.power) -> `perceived`, que o combo consulta entre os lotes de arrasto;
     *  - texto do evento (OCR em segundo plano, via scanEvent) -> evScreen;
     *  - forma da barra de encher (só na janela depois de um poder) -> barConfirmed;
     *  - coleta de quadros, se `record` estiver ligado.
     * Ele só PERCEBE; quem age é o Brain. Quando um minigame está rodando (`watcherHold`) o vigia não lê poder/texto/barra.
     */
    private suspend fun watch() {
        var lastBmp: Bitmap? = null
        val dataDir = File(debugDir, "dataset")
        while (currentCoroutineContext().isActive) {
            if (paused || last != Sc.COMBATE) { perceived = null; delay(80); continue }
            val fr = cap.latestFrame()
            if (fr == null || fr.first === lastBmp) { delay(4); continue }
            val b = fr.first
            lastBmp = b
            try {
                if (!watcherHold) {
                    // Comece a leitura do minigame antes das heurísticas dos poderes; logo após o especial, cada quadro conta.
                    scanEvent(b, fr.second)
                    val th = cfg.powerPct / 100.0
                    val p = Perf.time("watch_power") { Heur.power(b, th, 0.04) }
                    perceived = Perceived(fr.second, p)
                    watchBar(b)
                }
                if (cfg.record) record(b, fr.second, dataDir)
            } catch (e: Exception) {
                // uma leitura ruim não pode derrubar o vigia
            }
            // Cada quadro novo custa uma cópia de ~10 MB. Fora da janela de minigame (logo depois de usar um poder) 30 leituras por
            // segundo bastam para ver um poder carregar; dentro da janela, o ritmo máximo.
            delay(if (now() < evWindowUntil) 8L else 25L)
        }
    }

    /** Forma da barra de encher: confirma com 2 leituras seguidas (< 0,7 s) praticamente no mesmo ponto, como antes fazia o combatTick. */
    private fun watchBar(b: Bitmap) {
        if (!(cfg.barGame && now() >= evBlockUntil && now() < evWindowUntil && now() - lastShape > 50)) return
        lastShape = now()
        val s = Heur.snap(b)
        val bp = try { s.barCircle() } finally { s.release() }
        val prev = barSeen
        if (bp == null) {
            barSeen = null
        } else if (prev != null && now() - barSeenAt < 700 &&
            kotlin.math.abs(prev.x - bp.x) < 0.03f && kotlin.math.abs(prev.y - bp.y) < 0.03f
        ) {
            barSeen = null
            barConfirmed = bp
            barConfirmedAt = now()
        } else {
            barSeen = bp; barSeenAt = now()
        }
    }

    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    /**
     * Modo de coleta: no máximo 4 quadros por segundo em debug/dataset/, cada um com f_<horario>.jpg (1280 px de largura) e um
     * f_<horario>.json com o que o bot leu (estado, minigame, texto do evento, hud, roxo, brilho/rosa/X de cada slot, poderes).
     * Serve para treinar e conferir depois. Grava em outra thread; limite de 2000 arquivos na pasta.
     */
    private fun record(b: Bitmap, tsMs: Long, dir: File) {
        val n = now()
        if (n - lastRec < 250) return
        lastRec = n
        val pw = perceived?.power
        val mini = miniNow
        val ev = evScreen?.words?.take(8)?.joinToString(" ") { it.text } ?: ""
        val st = last?.name ?: "-"
        scope.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val base = "f_$tsMs"
                val sb = StringBuilder()
                val sn = Heur.snap(b)
                try {
                    sb.append("{\"t\":").append(tsMs)
                    sb.append(",\"estado\":\"").append(st).append("\"")
                    sb.append(",\"mini\":\"").append(mini).append("\"")
                    sb.append(",\"evento\":\"").append(ev).append("\"")
                    sb.append(",\"hud\":").append(f3(sn.hud())).append(",\"roxo\":").append(f3(sn.purple()))
                    sb.append(",\"slots\":[")
                    for (i in 3 downTo 1) {
                        if (i != 3) sb.append(",")
                        sb.append("{\"slot\":").append(i)
                        sb.append(",\"v\":").append(f3(sn.slotV(i)))
                        sb.append(",\"rosa\":").append(f3(sn.slotPink(i)))
                        sb.append(",\"x\":").append(sn.slotBlocked(i)).append("}")
                    }
                    sb.append("]")
                    if (pw != null) sb.append(",\"carregado_s3_s2_s1\":[${pw[0]},${pw[1]},${pw[2]}],\"x_s3_s2_s1\":[${pw[3]},${pw[4]},${pw[5]}]")
                    sb.append("}")
                } finally {
                    sn.release()
                }
                val ww = 1280
                val hh = (b.height * ww / b.width.toDouble()).toInt().coerceAtLeast(1)
                val sm = if (b.width > ww) Bitmap.createScaledBitmap(b, ww, hh, true) else b
                File(dir, "$base.jpg").outputStream().use { sm.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                File(dir, "$base.json").writeText(sb.toString())
                if (sm !== b) sm.recycle()
            }
            if (recN.incrementAndGet() % 100 == 0) DebugStore.trim(dir, 2000, 400L * 1024 * 1024) { false }
        }
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
        while (currentCoroutineContext().isActive && !paused && now() < end) {
            val bmp = cap.latest() ?: break
            val snap = Heur.snap(bmp)
            val p = try { snap.ball() } finally { snap.release() }
            if (p != null) { lastPt = p; misses = 0; seen = true } else misses++
            if (seen && misses >= 5) break
            if (!seen && now() - t0 > 3200) break
            val t = lastPt
            if (t == null) { delay(30); continue }
            // Rajadas com intervalo reduzido para aproximadamente o dobro da frequência; a bola pode se mover entre leituras.
            g.tapBurst(t.x * w, t.y * h, if (p != null) 6 else 2, 12L, 5L)
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
        while (currentCoroutineContext().isActive && !paused && now() < end) {
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
            // 3 arrastos seguidos na direção da seta, com duração reduzida para aproximadamente o dobro da velocidade.
            repeat(3) {
                if (!paused) g.swipe(
                    (cx - a.dx * half).coerceIn(0f, w - 1), (cy - a.dy * half).coerceIn(0f, h - 1),
                    (cx + a.dx * half).coerceIn(0f, w - 1), (cy + a.dy * half).coerceIn(0f, h - 1),
                    27,
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
        while (currentCoroutineContext().isActive && !paused && now() < end) {
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
            g.tapBurst(pt.x * w, pt.y * h, 6, 12L, 5L)
        }
    }

    /**
     * PING PONG (texto do jogo: "TOQUE PARA ARREMESSAR"): UM toque só, na hora em que o marcador passa pelo quadrado branco do centro
     * da barra (ampulheta). Em vez de um atraso fixo, o bot ACOMPANHA o marcador:
     *  1) A cada quadro lê a faixa da barra e guarda, por linha, o menor brilho já visto (o fundo parado). O marcador é o que aparece
     *     de brilhante por cima desse fundo (Heur.pingProfile + pingMarker). Funciona para qualquer seta/linha clara que se mova.
     *  2) Com 3 posições seguidas na mesma direção tira a velocidade e calcula quanto falta para chegar ao centro.
     *  3) Se ele vai PARA o centro, prevê a chegada, compensa o atraso do toque aprendido e toca.
     *     Se está se afastando, espera ele voltar (bate e volta). Se nunca achar o marcador (2,5 s), toca uma vez mesmo assim.
     * Depois do toque mede quando o marcador cruzou o ponto branco e ajusta a antecipação salva automaticamente.
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
        // Usa o atraso aprendido pelos acertos anteriores; antes de aprender, usa a mediana medida ou 90 ms iniciais.
        val ovMed = Perf.overheadMs.median()
        val measuredLead = if (ovMed != null && Perf.overheadMs.count() >= 5) ovMed else 90L
        val lead = (cfg.pingLearnedLeadMs.takeIf { it >= 0 }?.toLong() ?: measuredLead).coerceIn(0L, 400L)
        var lastBmp: Bitmap? = null
        var tapJob: Job? = null
        var tReq = 0L
        var yEst = 0f
        var vTap = 0f
        var idxTap = 0
        var frames = 0
        var saved = 0
        var tapped = false
        var why = ""
        while (currentCoroutineContext().isActive && !paused && now() - t0 < 7000) {
            val fr = cap.latestFrame()
            if (fr == null) { delay(5); continue }
            val b = fr.first
            if (b === lastBmp) { delay(3); continue }   // quadro repetido (a tela não mudou): não conta como nova leitura
            lastBmp = b
            Perf.gap("ping_gap")
            val tf = fr.second   // horário REAL da captura do quadro (ms) quando o carimbo é confiável; senão, o de agora
            val prof = Perf.time("ping_profile") { Heur.pingProfile(b, n) }
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
                            tReq = now()
                            yEst = yc + v * ((tReq - tc) / 1000f)   // onde o marcador deve estar no instante do pedido
                            vTap = v
                            idxTap = ys.size
                            // O toque sai em paralelo: o bot continua lendo quadros para ver QUANDO o marcador cruza o centro de verdade.
                            tapJob = scope.launch { if (!paused) g.tap(w * 0.565f, h * 0.48f) }
                            tapped = true
                            why = "v=%.2f".format(v)
                            break
                        }
                    }
                }
            }
            if (ys.isEmpty() && now() - t0 > 2500) { why = "sem marcador"; break }
        }
        if (!tapped && !paused) {
            g.tap(w * 0.565f, h * 0.48f)   // último recurso: um toque (não achou o marcador ou não alinhou a tempo)
            if (why.isEmpty()) why = "tempo esgotado"
        }
        // Registro do acerto: depois do toque, 600 ms de quadros para ver quando o marcador cruzou o centro de verdade.
        // Sai uma linha por ping pong em debug/pingpong_acertos.txt: compare "cruzouApos" com a antecipação usada (lead).
        // erroPrevisao > 0 = o marcador chegou DEPOIS do que o bot previu (toque cedo); < 0 = chegou antes (toque tarde).
        var sampled = false
        val tj = tapJob
        if (tapped && tj != null) {
            sampled = true
            val endAt = tReq + 600
            var lb: Bitmap? = lastBmp
            while (currentCoroutineContext().isActive && !paused && now() < endAt) {
                val fr2 = cap.latestFrame()
                if (fr2 == null) { delay(5); continue }
                if (fr2.first === lb) { delay(3); continue }
                lb = fr2.first
                val prof2 = Heur.pingProfile(fr2.first, n)
                val y2 = pingMarker(prof2, bg)
                if (y2 != null) { ts.add(fr2.second); ys.add(y2) }
            }
            tj.join()
            var cross = -1L
            for (i in maxOf(idxTap - 1, 0) until ys.size - 1) {
                val a = ys[i] - Heur.PING_CENTER_Y
                val c2 = ys[i + 1] - Heur.PING_CENTER_Y
                if (a * c2 <= 0f && ys[i + 1] != ys[i]) {
                    cross = ts[i] + ((ts[i + 1] - ts[i]) * (-a / (c2 - a))).toLong()
                    break
                }
            }
            val error = if (cross >= 0) cross - (tReq + lead) else Long.MIN_VALUE
            if (error != Long.MIN_VALUE) {
                // Erro positivo = tocou cedo; reduz a antecipação. Negativo = tocou tarde; aumenta. Corrige metade, limitado a 40 ms por rodada.
                val adjustment = (error / 2).coerceIn(-40L, 40L)
                cfg.pingLearnedLeadMs = (lead - adjustment).coerceIn(0L, 400L).toInt()
            }
            val linha = "${java.util.Date()} modo=auto lead=${lead}ms atrasoToque=${ovMed ?: -1}ms " +
                "v=${"%.3f".format(vTap)} yNoPedido=${"%.3f".format(yEst)} " +
                (if (cross >= 0) "cruzouApos=${cross - tReq}ms erroPrevisao=$error" else "cruzou=nao_visto") + "\n"
            runCatching { File(debugDir, "pingpong_acertos.txt").appendText(linha) }
            if (rec) log.append(linha)
        }
        if (rec) {
            lastBmp?.let { savePingCrop(it, "pp${k}_TAP") }
            log.append("TOQUE aos ${now() - t0}ms (${why}), antecipação ${lead}ms\n")
            runCatching { File(debugDir, "pingpong${k}_log_$t0.txt").writeText(log.toString()) }
        }
        say("Ping pong: toque ($why)")
        delay(if (sampled) 600L else 1200L)
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
        val waitMs = if (preferBar) 600L else 2200L   // dá mais tempo para a forma da bolinha/barra aparecer após o texto
        while (currentCoroutineContext().isActive && !paused && now() - t0 < waitMs) {
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
            delay(30)
        }
        // Antes, se só o texto disparou e a forma não apareceu, o bot tocava 4 s às cegas no ponto padrão (travava o combo em falso
        // alarme). Agora sem forma = não é minigame: não toca em nada.
        return false   // forma não achada: não conta como minigame confirmado (o chamador bloqueia o gatilho por um tempo)
    }

    private val artLogN = AtomicInteger(0)

    /** Pontuações da arte das cartas (debug/arte_scores.txt, no máximo 600 linhas): serve para ajustar os limites na tela de configurações. */
    private fun artLog(l: String) {
        if (artLogN.incrementAndGet() > 600) return
        runCatching { File(debugDir, "arte_scores.txt").appendText("${java.util.Date()} $l\n") }
    }

    private fun saveDebug(b: Bitmap, tag: String, quality: Int = 85) {
        runCatching {
            File(debugDir, "${tag}_${now()}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        }
    }
}
