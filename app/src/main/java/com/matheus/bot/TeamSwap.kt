package com.matheus.bot

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Montagem de times:
 *  - createTeam1: tela "CRIE SUA EQUIPE" (time vazio) -> arrasta Flash Reverso, Apocalipse e Nevasca para os
 *    quadrados 1, 2 e 3 -> botão CRIE SUA EQUIPE -> lobby -> grava quadros da tela de equipamentos.
 *  - run: troca do time 1 (morto) para o time 2 (EQUIPE -> seleciona até 3 personagens -> CONFIRMAR ALTERAÇÕES -> lobby).
 *
 * Cada passo confere o texto da tela antes de agir. Qualquer coisa diferente do esperado: salva um print
 * em debug/ e devolve a mensagem de erro (o Brain pausa).
 */
class TeamSwap(
    private val cap: Capture,
    private val text: TextVision,
    private val cfg: BotConfig,
    private val isPaused: () -> Boolean = { false },
    private val debug: (Bitmap, String) -> Unit,
) {
    /** Mensagens de progresso para o painel (o Brain liga isto ao texto do painel). */
    var say: (String) -> Unit = {}

    /** Linhas de diagnóstico da arte das cartas (o Brain grava em debug/arte_scores.txt). */
    var artLog: (String) -> Unit = {}
    private val artSaved = HashMap<String, Int>()
    private val artLogAt = HashMap<String, Long>()

    /** Altura (proporção da tela) da linha do NOME das cartas na lista de baixo. */
    private val listNameY = 0.772f

    // Catálogo fixo (times, equipamentos e geometria) separado da lógica de leitura e interação.
    private val targets = TeamCatalog.targets
    private val team1 = TeamCatalog.team1
    private val slotX = TeamCatalog.characterSlotX
    private val slotY = TeamCatalog.characterSlotY
    private val charLayout = TeamCatalog.characterLayout
    private val equipLayout = TeamCatalog.equipmentLayout
    private val equip1 = TeamCatalog.equip1
    private val equip2 = TeamCatalog.equip2
    private val lobbyCardX = TeamCatalog.lobbyCardX

    // Arrastar o personagem/item: segura 0,9 s PARADO em cima da carta e só então move por 0,7 s. Tem que segurar bastante antes
    // de mover: um arrasto "seco" (ou com pouca espera) é lido pelo jogo como rolagem e lança a lista. Era 0,6 s e ainda aparecia
    // um arrasto de rolagem de vez em quando.
    private val dragHoldMs = 900L
    private val dragMoveMs = 700L

    // Busca: equipamentos movem 600 ms e esperam 120 ms antes do OCR; personagens movem 190 ms e esperam 125 ms.
    // Ambos mantêm 110 ms parado no fim do gesto para a lista não ganhar impulso.
    private val charScrollMoveMs = 190L
    private val equipScrollMoveMs = 600L
    private val charScrollHoldMs = 110L
    private val charSettleMs = 125L
    private val equipScrollSettleMs = 120L

    // Faixas da tela (x0, y0, x1, y1) para ler só onde o botão está: o OCR da tela inteira é bem mais lento.
    private val rBottom = floatArrayOf(0f, 0.6f, 1f, 1f)       // RECEBER / EQUIPE do lobby
    private val rBack = floatArrayOf(0f, 0.8f, 1f, 1f)         // CANCELAR / VOLTAR
    private val rConfirm = floatArrayOf(0.4f, 0.05f, 1f, 0.5f) // CLASSIFICAR / SUBSTITUIÇÕES / CONFIRMAR ALTERAÇÕES

    /**
     * Quanto tempo cada rodada de arrastos dura antes de parar e analisar de novo (configurável no app, `phaseSec`; era 50 s fixos).
     * Uma rodada também termina antes se a lista parar de andar (chegou ao fim naquela direção).
     */
    private val phaseMs: Long get() = cfg.phaseSec.coerceIn(3, 120) * 1000L

    /** Parada (lista solta, sem arrastar) antes de analisar de novo ao fim de cada rodada. */
    private val settleMs = 500L

    /** As rodadas de arrasto, em ordem: -1 = arrasta para a ESQUERDA (avança na lista), +1 = para a DIREITA (volta). */
    private val phaseDirs = intArrayOf(-1, +1, -1)

    /**
     * PROTEÇÃO DE INÍCIO (personagens): antes de analisar e arrastar, espera a tela terminar a animação de entrada: só segue quando 2 leituras
     * seguidas da lista têm as mesmas cartas (no máximo 2 s; passou disso, segue assim mesmo). Sem isso o bot podia começar a rolar
     * enquanto a lista ainda estava entrando na tela.
     */
    private suspend fun waitListStable(maxMs: Long = 2000L) {
        val end = now() + maxMs
        var prev: Set<String>? = null
        var same = 0
        while (currentCoroutineContext().isActive && !isPaused() && now() < end) {
            val b = cap.latest()
            if (b == null) { delay(100); continue }
            val sig = listSig(text.read(b, 0f, 0.40f, 1f, 1f))
            val p = prev
            if (p != null && sameList(sig, p)) { same++; if (same >= 2) return } else same = 0
            prev = sig
            delay(120)
        }
    }

    /**
     * Fração do passo de rolagem: 1 = passo cheio (~4 cartas; sobram 2 a 3 de sobreposição para nenhuma ser pulada). Cai para 0,4
     * (~1,6 carta) se um passo andar demais (ver a segurança em pickAll). Uma para os personagens e outra para os equipamentos.
     */
    // COMEÇA em 0,4: o passo cheio (1) fazia a lista disparar no 1º arrasto depois de abrir o app, e só DEPOIS o freio de "passo grande
    // demais" (em pickAll) baixava para 0,4, que era o arrasto "normal". Como o freio vale só enquanto o app está aberto, todo início
    // começava brusco. Agora já começa no valor que funciona (o freio continua lá caso 0,4 ainda ande demais).
    @Volatile private var charStepScale = 0.4f
    @Volatile private var equipStepScale = 0.4f

    private fun now() = System.currentTimeMillis()

    /** Textos de botão de aceitar de uma janela de confirmação depois de arrastar um equipamento (palpite; o print mostra o real). */
    private val dialogOk = setOf("SIM", "CONFIRMAR", "EQUIPAR", "TROCAR", "MOVER", "SUBSTITUIR", "OK")
    /** Mantém a retomada após a substituição, sem tentar substituir o time outra vez se a equipagem falhar. */
    private var secondTeamCommitted = false

    private fun matches(wd: Word, t: Target) = if (t.exact) wd.text == t.key else wd.text.contains(t.key)

    private fun altMatch(wd: Word, t: Target) = t.alt != null && wd.text.contains(t.alt)

    /** Chave do alvo OU uma das palavras extras de lista (`listAlt`). Só usado ao procurar o item na lista de baixo. */
    private fun matchesInList(wd: Word, t: Target): Boolean =
        matches(wd, t) || (t.listAlt != null && t.listAlt.split('|').any { wd.text.contains(it) })

    /**
     * Leitura da lista de equipamentos em duas metades. O recorte mantém mais resolução nas letras pequenas das cartas.
     */
    private suspend fun readListBoosted(b: Bitmap): Screen {
        val left = text.read(b, 0f, 0.58f, 0.56f, 0.97f)
        val right = text.read(b, 0.44f, 0.58f, 1f, 0.97f)
        return Screen(left.words + right.words)
    }

    /** O artefato está no quadrado de destino `idx`? Não aceita o mesmo item em outro quadrado como concluído. */
    private fun slotHas(s: Screen, t: Target, idx: Int, lay: Layout): Boolean =
        s.words.any {
            (matches(it, t) || altMatch(it, t)) && it.cy in lay.slotBand &&
                abs(it.cx - lay.slotX[idx.coerceIn(0, 2)]) < lay.slotDx
        }

    /** Todas as chaves de equipamento do projeto (times 1 e 2): serve para reconhecer um item que não é o esperado. */
    private val allGearKeys: List<String> by lazy { (equip1 + equip2).flatten().map { it.key }.distinct() }

    /**
     * O quadrado idx está com o nome de OUTRO equipamento conhecido (não o pedido)? Devolve o texto lido, ou null se está vazio,
     * com o item certo ou com um nome que não conheço. Evita aceitar, por exemplo, o Traje do Flash no quadrado do Kama do Arkham.
     */
    private fun wrongItemName(s: Screen, t: Target, idx: Int, lay: Layout): String? {
        val own = listOfNotNull(t.key, t.alt)
        return s.words.firstNotNullOfOrNull { w ->
            if (w.cy !in lay.slotBand || abs(w.cx - lay.slotX[idx.coerceIn(0, 2)]) >= lay.slotDx) return@firstNotNullOfOrNull null
            if (own.any { w.text.contains(it) }) return@firstNotNullOfOrNull null
            if (allGearKeys.any { k -> w.text.contains(k) }) w.text else null
        }
    }

    /** Nomes-chave de todos os personagens conhecidos (times 1 e 2): serve para reconhecer, num quadrado, um personagem que NÃO é o esperado. */
    private val allCharKeys: List<String> by lazy { (team1 + targets).map { it.key }.distinct() }

    /**
     * O quadrado idx está com o nome de OUTRO personagem conhecido (não o pedido)? Devolve o texto lido, ou null se está vazio,
     * com o personagem certo ou com um texto que não conheço. Evita assumir "já está certo" com o time antigo no quadrado.
     */
    private fun wrongCharInSlot(s: Screen, t: Target, idx: Int, lay: Layout): String? {
        val own = listOfNotNull(t.key, t.alt)
        return s.words.firstNotNullOfOrNull { w ->
            if (w.cy !in lay.slotBand || abs(w.cx - lay.slotX[idx.coerceIn(0, 2)]) >= lay.slotDx) return@firstNotNullOfOrNull null
            if (own.any { w.text.contains(it) }) return@firstNotNullOfOrNull null
            if (allCharKeys.any { k -> w.text.contains(k) }) w.text else null
        }
    }

    /** O quadrado idx tem algum nome de item (qualquer um)? Serve para saber que não está vazio. */
    private fun slotOccupied(s: Screen, idx: Int, lay: Layout): Boolean =
        s.words.any {
            it.cy in lay.slotBand && abs(it.cx - lay.slotX[idx.coerceIn(0, 2)]) < lay.slotDx &&
                it.text.length >= 4 && it.text.any { c -> c.isLetter() }
        }

    /**
     * O personagem está DENTRO do quadrado idx? Olha só a faixa onde o nome aparece na carta do time
     * (y 0,28–0,50, perto do x do quadrado). NÃO exige o subtítulo: na carta pequena do quadrado o OCR quase
     * nunca lê "Contenção"/"Ninja", e exigir isso fazia o bot achar que o arrasto falhou (e tentar de novo,
     * rolando a lista) quando o personagem JÁ estava no quadrado. A versão certa é garantida na hora de escolher
     * a carta na lista (findName), onde o subtítulo é exigido.
     */
    private fun inSlot(s: Screen, t: Target, idx: Int, lay: Layout = charLayout): Boolean =
        s.words.any {
            (matches(it, t) || altMatch(it, t)) && it.cy in lay.slotBand && abs(it.cx - lay.slotX[idx.coerceIn(0, 2)]) < lay.slotDx
        }


    /** Se o alvo exige subtítulo, ele tem que estar logo acima do nome (mesma carta). */
    private fun subOk(s: Screen, name: Word, t: Target): Boolean {
        val sub = t.sub ?: return true
        val subs = sub.split('|')
        return s.words.any { w -> subs.any { w.text.contains(it) } && abs(w.cx - name.cx) < 0.08f && (name.cy - w.cy) in 0.015f..0.08f }
    }

    /** Há algum texto de subtítulo (qualquer um) logo acima do nome? Se não há, o OCR simplesmente não leu. */
    private fun hasSubtitleAbove(s: Screen, name: Word): Boolean =
        s.words.any { it !== name && abs(it.cx - name.cx) < 0.08f && (name.cy - it.cy) in 0.015f..0.08f }

    /**
     * Nome do alvo na faixa de altura dada (o mais à esquerda), respeitando a VERSÃO da carta: subtítulo certo,
     * sem o subtítulo errado e nível mínimo (quando o OCR lê; se não leu, não reprova).
     * `relaxed`: aceita também a carta cujo subtítulo o OCR não leu (nenhum texto acima do nome). Usado só na
     * 2ª rodada em diante, para o bot não ficar procurando para sempre uma carta que está na tela com o subtítulo ilegível.
     */
    private fun findName(s: Screen, t: Target, yMin: Float = 0f, yMax: Float = 1f, relaxed: Boolean = false): Word? =
        s.words.filter {
            matchesInList(it, t) && it.cy in yMin..yMax &&
                (subOk(s, it, t) || (relaxed && !t.strict && !hasSubtitleAbove(s, it))) &&
                !badSub(s, it, t) && levelOk(s, it, t)
        }.minByOrNull { it.cx }

    /** A carta tem o subtítulo da versão ERRADA logo acima do nome? `notSub` aceita alternativas separadas por "|". */
    private fun badSub(s: Screen, name: Word, t: Target): Boolean {
        val bads = (t.notSub ?: return false).split('|')
        return s.words.any { w -> bads.any { w.text.contains(it) } && abs(w.cx - name.cx) < 0.08f && (name.cy - w.cy) in 0.015f..0.08f }
    }

    /** Nível lido no canto superior direito da carta na lista (null = não consegui ler). */
    private fun levelOf(s: Screen, name: Word): Int? =
        s.words.filter { (it.cx - name.cx) in 0.03f..0.13f && (it.cy - name.cy) in -0.37f..-0.25f }
            .mapNotNull { it.text.toIntOrNull() }.filter { it in 1..99 }.maxOrNull()

    private fun levelOk(s: Screen, name: Word, t: Target): Boolean {
        if (t.minLevel <= 0) return true
        val lv = levelOf(s, name) ?: return true
        return lv >= t.minLevel
    }

    /**
     * Acha a carta do personagem na lista de baixo usando TEXTO + ARTE (CardArt). Devolve uma "palavra" na posição do nome da carta,
     * como o findName, então o resto do fluxo (arrastar pelo corpo da carta acima do nome) não muda.
     *  - O texto achou a carta: no modo 2 e nas duas primeiras rodadas, se a arte nessa posição for claramente de OUTRA versão
     *    (pontuação < artVeto), rejeita. Na 3ª rodada vale só o texto (válvula de segurança se a arte estiver mal calibrada).
     *  - O texto NÃO achou (nome ilegível sobre arte brilhante, subtítulo não lido): a arte decide se a pontuação for >= artAccept;
     *    o nível lido e o subtítulo da versão errada ainda valem.
     * Sem referência de arte para o personagem, ou modo 0: igual ao findName.
     */
    private fun findCard(b: Bitmap, s: Screen, t: Target, yMin: Float, relaxed: Boolean, phase: Int): Word? {
        val ocr = findName(s, t, yMin = yMin, relaxed = relaxed)
        if (cfg.artMode <= 0 || !CardArt.has(t.key)) return ocr
        val acc = cfg.artAccept / 100f
        val veto = cfg.artVeto / 100f
        if (ocr != null) {
            val sc = CardArt.scoreNear(b, t.key, ocr.cx) ?: return ocr
            if (cfg.artMode >= 2 && phase < 2 && sc < veto) {
                artLogThrottled("veto_${t.key}", "VETO ${t.label}: o texto achou em x=${"%.3f".format(ocr.cx)}, mas a arte pontuou ${"%.2f".format(sc)} (< ${"%.2f".format(veto)}) rodada=${phase + 1}")
                return null
            }
            return ocr
        }
        val h = CardArt.best(b, t.key) ?: return null
        if (h.score < acc) return null
        val w = Word(t.key, h.cx, listNameY, 0.10f, 0.03f)
        if (!levelOk(s, w, t) || badSub(s, w, t)) return null
        artLogThrottled("ok_${t.key}", "ACEITO ${t.label} só pela arte: x=${"%.3f".format(h.cx)} pontuação=${"%.2f".format(h.score)} (>= ${"%.2f".format(acc)}) rodada=${phase + 1}")
        val n = artSaved.getOrDefault(t.key, 0)
        if (n < 3) { artSaved[t.key] = n + 1; debug(b, "arte_aceito_${t.key.replace(' ', '_')}") }   // print para você conferir a carta aceita
        return w
    }

    private fun artLogThrottled(tag: String, msg: String) {
        val n = now()
        if (n - (artLogAt[tag] ?: 0L) < 2000) return
        artLogAt[tag] = n
        artLog(msg)
    }

    /** Nas paradas para analisar: grava a melhor pontuação da arte de cada personagem que falta (para calibrar artAccept/artVeto). */
    private fun logArtScores(b: Bitmap, list: List<Target>, done: BooleanArray, phase: Int) {
        if (cfg.artMode <= 0) return
        val parts = list.indices.filter { !done[it] && CardArt.has(list[it].key) }.mapNotNull { k ->
            CardArt.best(b, list[k].key)?.let { "${list[k].key}=${"%.2f".format(it.score)}@${"%.2f".format(it.cx)}" }
        }
        if (parts.isNotEmpty()) artLogThrottled("parada", "parada rodada=${phase + 1} " + parts.joinToString(" "))
    }

    /** Palavra mais à esquerda que combina com a chave dentro da faixa de altura dada. */
    private fun first(s: Screen, key: String, exact: Boolean, yMin: Float = 0f, yMax: Float = 1f): Word? =
        s.words.filter { (if (exact) it.text == key else it.text.contains(key)) && it.cy in yMin..yMax }
            .minByOrNull { it.cx }

    /**
     * Quadrado do time com carta? Mede a variação de cor no miolo do quadrado (vazio = cinza escuro liso; com
     * personagem = arte colorida). Só serve quando o quadrado começa VAZIO (tela "CRIE SUA EQUIPE").
     */
    private fun slotFilled(b: Bitmap, idx: Int): Boolean {
        val cx = slotX[idx.coerceIn(0, 2)]
        val x0 = ((cx - 0.045f) * b.width).toInt().coerceIn(0, b.width - 1)
        val x1 = ((cx + 0.045f) * b.width).toInt().coerceIn(x0 + 1, b.width)
        val y0 = (0.08f * b.height).toInt().coerceIn(0, b.height - 1)
        val y1 = (0.30f * b.height).toInt().coerceIn(y0 + 1, b.height)
        val stepX = maxOf(1, (x1 - x0) / 14)
        val stepY = maxOf(1, (y1 - y0) / 14)
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = b.getPixel(x, y)
                val l = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                sum += l; sum2 += l * l; n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return false
        val mean = sum / n
        val sd = sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0))
        return sd > 28.0
    }


    /**
     * Nomes lidos na lista de baixo: serve para saber se a lista andou depois de rolar. Só palavras com letras (os números
     * de poder/vida mudam de leitura em leitura e atrapalhavam a comparação).
     */
    private fun listSig(s: Screen): Set<String> =
        s.words.filter { it.cy in 0.55f..0.92f && it.text.length >= 4 && it.text.any { c -> c.isLetter() } }.map { it.text }.toSet()

    /**
     * Mesma lista = quase TODAS as palavras iguais. Antes bastava 80%, mas uma rolagem de 1,3 carta mantém ~80% das
     * palavras na tela, então o bot achava que a lista não andava e voltava ao início antes de chegar ao final.
     */
    private fun sameList(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        return a.intersect(b).size >= 0.95 * maxOf(a.size, b.size)
    }


    /**
     * Completa os 3 quadrados. Para cada personagem que falta:
     *  1) Primeiro só ANALISA a tela, parado. Se algum dos que faltam está na lista, arrasta (segurando) para o quadrado.
     *  2) Se não achou, 1ª rodada: arrasta a lista para a ESQUERDA, lendo a tela a cada passo, por `phaseMs`.
     *  3) Acabou o tempo (ou a lista parou de andar): personagens fazem as rodadas finitas; equipamentos invertem
     *     a direção continuamente (ping-pong), sem pausa longa, até localizar o item.
     *  4) Acabou a 3ª rodada: para, analisa e começa outra varredura se ainda faltar algo.
     * Depois de arrastar um personagem: solta, volta a ANALISAR (parado). Se a carta ainda aparece na lista, tenta de novo sem limite;
     * se saiu da lista, está no quadrado. Se ainda há outro personagem à vista, arrasta também;
     * se não, CONTINUA rolando na mesma direção e rodada de onde estava (não volta ao início nem recomeça as rodadas).
     * `substitutionMode`: usa uma trajetória de arrasto direta primeiro e uma trajetória em dois trechos como alternativa na troca do time.
     * Na criação inicial do time, usa a trajetória em dois trechos. Equipamentos mantêm gestos próprios.
     * Devolve null se os 3 foram arrastados, ou a mensagem do que falhou.
     */
    private suspend fun pickAll(
        g: BotAccessibilityService, list: List<Target>, substitutionMode: Boolean = false, lay: Layout = charLayout,
    ): String? {
        val done = BooleanArray(list.size)
        val attempts = LongArray(list.size)
        if (!lay.staysInList) waitListStable()   // proteção de início: só começa com a lista parada
        var dragged = -1         // último personagem arrastado ainda não confirmado (confirma na próxima leitura)
        var phase = 0            // índice em phaseDirs: 0 = 1ª rodada (esquerda), 1 = direita, 2 = 3ª (esquerda); 3 = acabou
        var phaseStart = -1L     // -1 = rodada ainda não começou: primeiro só analisa a tela, parado
        var steps = 0
        var stuck = 0
        var offPicker = 0
        var confirmMiss = 0      // leituras seguidas em que a confirmação do nome (antes de arrastar) não bateu
        var lastSig: Set<String> = emptySet()
        while (currentCoroutineContext().isActive && !isPaused()) {
            if (isPaused()) return "Troca pausada"
            val b = cap.latest()
            if (b == null) { delay(200); continue }
            // Leia os quadrados só quando parado. Reutilize esse OCR na leitura principal para não analisar a mesma região duas vezes.
            val equipmentSlots = if (lay.staysInList && (phaseStart < 0 || dragged >= 0))
                text.read(b, 0.15f, 0.28f, 0.85f, 0.52f)
            else null
            if (equipmentSlots != null) {
                for (i in list.indices) if (!done[i] && inSlot(equipmentSlots, list[i], i, lay)) done[i] = true
                if (done.all { it }) return null
            }
            // Enquanto rola, lê só a lista. Parado, combina as duas metades da lista com o OCR dos quadrados já feito acima;
            // após um arrasto, acrescenta uma faixa de estado para verificar modal ou mudança de tela.
            val rolling = phaseStart >= 0 && dragged < 0
            val s = when {
                lay.staysInList && rolling -> readListBoosted(b)
                lay.staysInList -> {
                    // No primeiro scan, priorize as cartas visíveis e os quadrados já lidos; evite OCR da tela inteira.
                    // Depois de arrastar, acrescente uma faixa de estado para detectar confirmação/modal ou saída do seletor.
                    val items = readListBoosted(b)
                    val state = if (dragged >= 0) text.read(b, 0.15f, 0.15f, 1f, 0.82f) else null
                    Screen(equipmentSlots!!.words + items.words + (state?.words ?: emptyList()))
                }
                rolling -> text.read(b, 0f, 0.40f, 1f, 1f)
                else -> text.read(b)
            }
            if (!lay.staysInList && !rolling) logArtScores(b, list, done, phase)
            for (i in list.indices) {
                if (!done[i] && inSlot(s, list[i], i, lay)) {
                    done[i] = true
                    if (dragged != i) debug(b, "quadrado${i + 1}_ja_cheio")   // diagnóstico: achou o quadrado cheio sem o bot ter arrastado
                }
            }
            // Equipamento: item ERRADO num quadrado (ex.: o Traje do Flash no quadrado do Kama) nunca conta como feito:
            // desmarca e o fluxo abaixo arrasta o item certo por cima. (O quadrado que acabou de ser arrastado é conferido mais abaixo.)
            if (lay.staysInList) {
                for (k in list.indices) {
                    if (dragged == k) continue
                    val wr = wrongItemName(s, list[k], k, lay) ?: continue
                    if (done[k]) {
                        done[k] = false
                        debug(b, "equip_item_errado_${k + 1}")
                        say("Quadrado ${k + 1} tem item errado ($wr): vou trocar por ${list[k].label}.")
                    }
                    if (attempts[k] == 0L || attempts[k] % 4L == 0L)
                        say("Quadrado ${k + 1} ainda tem $wr; vou continuar tentando ${list[k].label}.")
                }
            }
            val relaxed = phase >= 1   // da 2ª rodada em diante aceita carta com subtítulo ilegível
            // Equipamento: se o jogo abriu uma janela de confirmação depois do arrasto (ex.: item em uso por OUTRO personagem), toca no
            // botão de aceitar e guarda um print (debug/equip_janela_*) para a gente ver o texto real dela.
            if (lay.staysInList && dragged >= 0) {
                val ok = s.words.firstOrNull {
                    it.text in dialogOk && it.cy in 0.25f..0.80f && it.cx in 0.20f..0.80f
                }
                if (ok != null) {
                    debug(b, "equip_janela")
                    g.tap(ok.cx * b.width, ok.cy * b.height)
                    delay(900)
                    continue
                }
                // Se a tela mudou ou o OCR falhou, espera e volta a reconhecer; não encerra a equipagem por uma leitura transitória.
                if (s.has("CLASSIFICADO") || s.has("PONTUACAO")) offPicker = 0
                else {
                    offPicker++
                    if (offPicker >= 3) {
                        debug(b, "equip_tela_outra")
                        say("Tela de equipamentos não reconhecida; tentando reconhecer de novo.")
                        offPicker = 0
                    }
                    delay(500)
                    continue
                }
            }
            // Confere o último arrasto: a carta saiu da lista (está no quadrado) ou continua lá (não pegou: tenta de novo abaixo).
            if (dragged >= 0) {
                val d = dragged
                dragged = -1
                if (!done[d]) {
                    val wrong = if (lay.staysInList) wrongItemName(s, list[d], d, lay) else null
                    if (wrong != null) {
                        // O quadrado ficou com OUTRO item (não o pedido): não aceita; a busca abaixo arrasta o item certo por cima.
                        debug(b, "equip_item_errado")
                        say("Quadrado ${d + 1} ficou com item errado ($wrong). Tentando de novo: ${list[d].label}")
                        say("O quadrado ${d + 1} continua com $wrong; vou repetir o gesto para ${list[d].label}.")
                    } else if (lay.staysInList) {
                        // Não confunda carta fora da área visível com item equipado. Se não há confirmação no espaço,
                        // o fluxo volta a procurar o item em toda a lista e tenta novamente quando encontrá-lo.
                        if (attempts[d] % 4L == 0L) {
                            debug(b, "equip_nao_confirmado")
                            say("${list[d].label} ainda não foi confirmado no quadrado ${d + 1}; vou tentar de novo.")
                        }
                    } else if (substitutionMode) {
                        val selected = inSlot(s, list[d], d, lay)
                        val stillInList = findCard(b, s, list[d], lay.stripMinY, relaxed, phase) != null
                        if (selected || !stillInList) done[d] = true
                        else say("${list[d].label} continua na lista; vou repetir o arrasto.")
                    } else if (findCard(b, s, list[d], lay.stripMinY, relaxed, phase) == null) done[d] = true
                    else say("${list[d].label} continua na lista; vou repetir o arrasto.")
                }
            }
            if (done.all { it }) return null

            // Personagens: texto + arte (findCard). Equipamentos: só texto, como antes.
            val found = HashMap<Int, Word>()
            for (k in list.indices) {
                if (done[k]) continue
                val fw = if (lay.staysInList) findName(s, list[k], yMin = lay.stripMinY, relaxed = relaxed)
                else findCard(b, s, list[k], lay.stripMinY, relaxed, phase)
                if (fw != null) found[k] = fw
            }
            val i = list.indices.firstOrNull { found.containsKey(it) }
            if (i != null) {
                val t = list[i]
                val w0 = found.getValue(i)
                // Antes de arrastar um equipamento, confira novamente o quadrado de destino num quadro recente.
                // O item pode já ter sido equipado numa tentativa anterior ou reconhecido com atraso pelo OCR da lista.
                if (lay.staysInList) {
                    val latest = cap.latest()
                    if (latest != null) {
                        val slotsNow = text.read(latest, 0.15f, 0.28f, 0.85f, 0.52f)
                        if (inSlot(slotsNow, t, i, lay)) {
                            done[i] = true
                            debug(latest, "equip_${i + 1}_ja_equipado_antes_arrasto")
                            say("${t.label} já está equipado no quadrado ${i + 1}; seguindo para o próximo item.")
                            continue
                        }
                    }
                }
                // Personagens: ANTES de arrastar confirma o NOME num quadro NOVO (a leitura de cima pode ser de uns 0,3 s atrás e a lista
                // pode ter mexido). Se o personagem certo não está mais ali (ou andou), não arrasta: volta a analisar. Usa a posição
                // nova. Depois de 3 conferências seguidas sem bater, arrasta mesmo assim com a leitura de cima (já tem versão e nível conferidos).
                val w = if (lay.staysInList) w0 else {
                    val b2 = cap.latest()
                    val s2 = if (b2 != null) text.read(b2, 0f, 0.40f, 1f, 1f) else null
                    val w2 = if (s2 != null && b2 != null) findCard(b2, s2, t, lay.stripMinY, relaxed, phase) else null
                    if (w2 != null && abs(w2.cx - w0.cx) <= 0.05f && abs(w2.cy - w0.cy) <= 0.03f) {
                        confirmMiss = 0
                        w2
                    } else if (++confirmMiss < 3) {
                        say("Confirmando ${t.label}: não confere, lendo de novo")
                        continue
                    } else {
                        confirmMiss = 0
                        w0
                    }
                }
                if (isPaused()) return "Troca pausada"
                // Equipamento: pega pela linha INTEIRA do nome (centro da carta), não só pela palavra-chave, que fica mais à esquerda.
                val c = if (lay.staysInList)
                    s.words.filter {
                        abs(it.cy - w.cy) < 0.012f && abs(it.cx - w.cx) < 0.07f && it.text.contains(w.text) &&
                            // linha que junta o nome de 2 cartas vizinhas tem o centro ENTRE elas (pegava a carta errada): ignora
                            allGearKeys.none { k -> k != t.key && it.text.contains(k) }
                    }.maxByOrNull { it.text.length } ?: w
                else w
                val sx = c.cx * b.width
                val sy = w.cy * b.height - b.height * lay.srcDy   // corpo da carta, acima do nome
                val ex = lay.slotX[i.coerceIn(0, 2)] * b.width
                val ey = lay.slotY * b.height
                say("Arrastando ${t.label} para o quadrado ${i + 1}")
                attempts[i]++
                if (lay.staysInList) {
                    // Equipamento: testa quatro gestos diferentes e repete o ciclo com mais tempo de contato se nenhum for confirmado.
                    // Cada tentativa salva um print do resultado para diagnóstico.
                    val v = ((attempts[i] - 1) % 4).toInt()
                    val ciclo = ((attempts[i] - 1) / 4).coerceAtMost(3L)
                    val hold = dragHoldMs + ciclo * 300L
                    val move = dragMoveMs + ciclo * 200L
                    say("Equipando ${t.label} no quadrado ${i + 1} (tentativa ${attempts[i]})")
                    val occupiedTarget = slotOccupied(s, i, lay)
                    if (occupiedTarget) {
                        // Se o espaço já contém um artefato, não toque nele: isso selecionava/abria o item errado.
                        // Use somente arrastos para substituir o que está equipado.
                        when (v) {
                            0 -> g.drag(sx, sy, ex, ey, hold, move, 700L + ciclo * 200L)
                            1 -> g.dragVia(sx, sy, sx, b.height * 0.52f, ex, ey, hold, 450L + ciclo * 100L, 550L + ciclo * 100L, 700L + ciclo * 200L)
                            2 -> g.dragVia(sx, sy, sx, b.height * 0.56f, ex, ey, hold + 150L, 500L + ciclo * 100L, 650L + ciclo * 100L, 700L + ciclo * 200L)
                            else -> g.drag(sx, sy, ex, ey, hold + 250L, move + 200L, 900L)
                        }
                    } else when (v) {
                        0 -> g.drag(sx, sy, ex, ey, hold, move, 500L + ciclo * 200L)                 // segura e arrasta direto
                        1 -> g.dragVia(sx, sy, sx, b.height * 0.52f, ex, ey, hold, 400L + ciclo * 100L, 500L + ciclo * 100L, 500L + ciclo * 200L)
                        2 -> { g.tap(sx, sy); delay(800L + ciclo * 200L); g.tap(ex, ey) }           // slot vazio: tenta tocar no item e no destino
                        else -> g.dragVia(sx, sy, sx, b.height * 0.56f, ex, ey, hold, 500L + ciclo * 100L, 650L + ciclo * 100L, 700L + ciclo * 200L)
                    }
                    delay(900)
                    cap.latest()?.let { debug(it, "equip_tentativa${attempts[i]}_${t.key}") }
                } else if (substitutionMode && attempts[i] == 1L) {
                    // Na tela de substituição, tenta primeiro um arrasto direto da carta para o espaço morto.
                    g.drag(sx, sy, ex, ey, 650L, 700L, 650L)
                } else {
                    // Segura PARADO em cima da carta (dragHoldMs, sem mexer o dedo), e SEM soltar sobe reto para fora da faixa da lista
                    // (assim o jogo não lê como rolagem lateral) e só depois vai até o quadrado, onde fica parado antes de soltar.
                    g.dragVia(sx, sy, sx, b.height * 0.40f, ex, ey, dragHoldMs, 350L, 450L, 650L)
                }
                delay(if (lay.staysInList || substitutionMode) 700L else 400L) // deixa o arrasto assentar
                dragged = i  // confirma na próxima leitura (a carta tem que ter saído da lista)
                // A lista mudou (a carta saiu): zera a comparação de "parou de andar", mas continua na mesma rodada e direção.
                if (phaseStart >= 0) phaseStart = now()
                steps = 0; stuck = 0; lastSig = emptySet()
                continue
            }

            // PROTEÇÃO (equipamentos): o item que o PRÓPRIO personagem já usa SOME da lista dele (visto nas fotos). Então, se um item
            // que falta não está na lista mas aparece em algum quadrado, ele já está equipado: não precisa procurar nem arrastar.
            // (Foi o que travou o Flash: tudo já equipado, o OCR não leu o nome da armadura no quadrado e o bot rolou 3 rodadas.)
            if (lay.staysInList) {
                for (k in list.indices) {
                    if (!done[k] && slotHas(s, list[k], k, lay)) { done[k] = true; debug(b, "equip_${k + 1}_ja_equipado") }
                }
                if (done.all { it }) return null
            }

            // Nenhum dos que faltam está visível (análise parada).
            val falta = list.filterIndexed { k, _ -> !done[k] }.joinToString { it.label }
            if (phase >= phaseDirs.size) {
                // Equipamento que sumiu da lista e cujo quadrado tem algum item: assume que está equipado (o jogo tira da lista o
                // que o personagem usa) e guarda um print para você conferir. Quadrado vazio = falha de verdade.
                if (lay.staysInList) {
                    var todos = true
                    for (k in list.indices) {
                        if (done[k]) continue
                        if (slotOccupied(s, k, lay) && wrongItemName(s, list[k], k, lay) == null) { done[k] = true; debug(b, "equip_${k + 1}_assumido") } else todos = false
                    }
                    if (todos && done.all { it }) return null
                } else {
                    // PROTEÇÃO (personagens): quem está no time some da lista de baixo. Se depois de varrer a lista inteira o personagem
                    // não apareceu e o quadrado tem algo lido que NÃO é o nome de outro personagem conhecido, assume que já está certo
                    // (o OCR só não leu o nome na carta pequena) e guarda um print (debug/personagem_N_assumido) para você conferir.
                    var todos = true
                    for (k in list.indices) {
                        if (done[k]) continue
                        if (slotOccupied(s, k, lay) && wrongCharInSlot(s, list[k], k, lay) == null) {
                            done[k] = true
                            debug(b, "personagem_${k + 1}_assumido")
                            say("${list[k].label} não está na lista: assumo que já está no quadrado ${k + 1}")
                        } else todos = false
                    }
                    if (todos && done.all { it }) return null
                }
                val missing = list.filterIndexed { k, _ -> !done[k] }.joinToString { it.label }
                say("Ainda procurando $missing; vou reiniciar a varredura da lista.")
                // Equipamentos ficam alternando: a sequência continua direita/esquerda sem reiniciar sempre para o mesmo lado.
                phase = if (lay.staysInList) 1 else 0
                phaseStart = -1L
                steps = 0
                stuck = 0
                lastSig = emptySet()
                if (!lay.staysInList) delay(settleMs)
                continue
            }
            if (phaseStart < 0) phaseStart = now()   // começa a rodada de arrastos
            steps++
            val sig = listSig(s)
            if (sameList(sig, lastSig)) stuck++ else stuck = 0
            // Segurança do passo grande: se quase nenhuma palavra se repete entre duas leituras seguidas, a lista andou demais e pode
            // ter pulado cartas. Reduz o passo (vale para as próximas rodadas e trocas); a rodada na outra direção cobre o buraco.
            val curScale = if (lay.staysInList) equipStepScale else charStepScale
            if (stuck == 0 && sig.size >= 6 && lastSig.size >= 6 && curScale > 0.5f &&
                sig.intersect(lastSig).size < 0.10f * minOf(sig.size, lastSig.size)
            ) {
                if (lay.staysInList) equipStepScale = 0.4f else charStepScale = 0.4f
                say("Passo grande demais (poucas cartas em comum). Reduzindo o passo.")
            }
            lastSig = sig
            if (now() - phaseStart > phaseMs || stuck >= 3) {
                // Fim da rodada (tempo ou fim da lista): inverte logo a direção dos equipamentos; a leitura após cada gesto
                // já inclui o tempo curto para a lista assentar. Personagens mantêm a pausa entre rodadas.
                val dirTxt = if (phaseDirs[phase] > 0) "direita" else "esquerda"
                phase++
                phaseStart = -1L; steps = 0; stuck = 0; lastSig = emptySet()
                say(if (lay.staysInList) "Fim do trecho para a $dirTxt sem achar $falta; invertendo o arrasto e continuando a busca."
                    else "Parei de arrastar para a $dirTxt sem achar $falta. Analisando de novo...")
                if (!lay.staysInList) delay(settleMs)
                continue
            }
            val dir = phaseDirs[phase]
            say("Procurando $falta (${if (dir > 0) "direita" else "esquerda"}, rodada ${phase + 1}, passo $steps)")
            // ~4 cartas por passo (personagens: carta ~0,14 da largura e ~6 visíveis; equipamentos: ~0,12 e ~7 visíveis; sobram 2 a 3).
            // Solta parado (scrollStop): não lança a lista, ela anda só a distância pedida.
            val y = b.height * 0.74f
            val sc = if (lay.staysInList) equipStepScale else charStepScale
            val mid = (lay.scrollFrom + lay.scrollTo) / 2f
            val half = (lay.scrollFrom - lay.scrollTo) / 2f * sc
            val fromX = b.width * (mid + half)
            val toX = b.width * (mid - half)
            val mvMs = if (lay.staysInList) equipScrollMoveMs else charScrollMoveMs
            val hdMs = charScrollHoldMs
            if (dir > 0) g.scrollStop(toX, y, fromX, y, mvMs, hdMs)
            else g.scrollStop(fromX, y, toX, y, mvMs, hdMs)
            delay(if (lay.staysInList) equipScrollSettleMs else charSettleMs)
        }
        return if (isPaused()) "Troca pausada" else "Troca cancelada"
    }

    /**
     * Tela "CRIE SUA EQUIPE" (time 1 vazio): arrasta Flash Reverso, Apocalipse e Nevasca para os quadrados 1, 2 e 3,
     * toca no botão CRIE SUA EQUIPE, espera o lobby e grava quadros da tela de equipamentos do 1º personagem.
     * Devolve null se deu certo ou a mensagem do que falhou.
     */
    suspend fun createTeam1(g: BotAccessibilityService): String? {
        if (isPaused()) return "Troca pausada"
        pickAll(g, team1)?.let { return it }
        // Conferência só informativa e rápida (uma leitura, sem esperar): o jogo não deixa criar a equipe sem os 3 quadrados cheios, então
        // se algo estiver errado o lobby não aparece e o erro sai logo abaixo (com print em debug/). Antes esperava até 2,5 s aqui.
        cap.latest()?.let { b ->
            val s = text.read(b, 0f, 0f, 1f, 0.5f)
            if (!team1.withIndex().all { inSlot(s, it.value, it.index) }) debug(b, "time1_nao_li_quadrados")
        }
        // O botão fica no meio da tela (y ~0,33); o título "CRIE SUA EQUIPE" está colado no topo e não conta.
        // Toca bem em cima da palavra "CRIE" (lado esquerdo do botão, x ~0,65 / y ~0,335 no seu print). O x é fixo
        // porque o leitor de texto pode devolver a linha inteira e o centro dela cairia mais para a direita.
        // Se a tela demorar para mudar, continua relendo e tentando até aparecer o lobby.
        var lobbyOk = false
        while (!lobbyOk && !isPaused()) {
            val current = cap.latest()
            if (current == null) { delay(200); continue }
            val currentText = text.read(current)
            if (currentText.has("RECEBER", yMin = 0.7f) && currentText.has("EQUIPE", yMin = 0.7f)) {
                lobbyOk = true
                break
            }
            if (!currentText.has("CRIE SUA EQUIPE")) {
                say("Aguardando a tela CRIE SUA EQUIPE ou o lobby...")
                delay(500)
                continue
            }
            val y = first(currentText, "CRIE", false, 0.2f, 0.55f)?.cy ?: 0.335f
            g.tap(current.width * 0.65f, current.height * y)
            val r = waitText(4000, rBottom) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) }
            if (r != null) { lobbyOk = true; break }
        }
        if (isPaused()) return "Troca pausada"
        delay(300)
        return equipTeam(g, equip1, team1)   // equipa Flash Reverso, Apocalipse e Nevasca (mesma lógica de arrasto, agora pelo nome do equipamento)
    }

    private suspend fun waitText(
        timeoutMs: Long, region: FloatArray? = null, pred: (Screen) -> Boolean,
    ): Pair<Bitmap, Screen>? {
        val end = now() + timeoutMs
        while (currentCoroutineContext().isActive && !isPaused() && now() < end) {
            val b = cap.latest()
            if (b != null) {
                // `region` = só lê essa faixa da tela (bem mais rápido). As posições lidas continuam em proporção da tela inteira.
                val s = if (region == null) text.read(b) else text.read(b, region[0], region[1], region[2], region[3])
                if (pred(s)) return b to s
            }
            delay(70)
        }
        return null
    }

    private fun confirmEnabled(s: Screen): Boolean =
        s.has("CONFIRMAR") && s.has("ALTERACOES") && !s.has("NECESSARIO")

    private fun isReplacementScreen(s: Screen): Boolean =
        s.has("SUBSTITUICOES") && (s.has("CLASSIFICAR") || s.has("CONFIRMAR"))

    private fun isTeamLobby(s: Screen): Boolean =
        s.has("RECEBER", yMin = 0.7f) && s.has("EQUIPE", yMin = 0.7f)

    /** Evita tocar num CONFIRMAR desativado ou num texto de confirmação de outra etapa. */
    private suspend fun waitConfirmEnabled(timeoutMs: Long): Pair<Bitmap, Screen>? {
        val end = now() + timeoutMs
        var enabledFrames = 0
        while (currentCoroutineContext().isActive && !isPaused() && now() < end) {
            val b = cap.latest()
            if (b != null) {
                val s = text.read(b, rConfirm[0], rConfirm[1], rConfirm[2], rConfirm[3])
                if (confirmEnabled(s)) {
                    if (++enabledFrames >= 2) return b to s
                } else enabledFrames = 0
            }
            delay(100)
        }
        return null
    }

    private suspend fun tapWord(g: BotAccessibilityService, b: Bitmap, w: Word) {
        g.tap(w.cx * b.width, w.cy * b.height)
    }

    private fun fail(msg: String): String {
        cap.latest()?.let { debug(it, "troca_falha") }
        return msg
    }

    /** Troca do time morto para o time 2. Devolve null se deu certo, ou a mensagem do que falhou. */
    suspend fun run(g: BotAccessibilityService): String? {
        if (isPaused()) return "Troca pausada"
        // Se a substituição já foi confirmada, retoma pela equipagem. Evita abrir outra vez a tela de troca após uma falha transitória.
        if (secondTeamCommitted) return equipTeam(g, equip2, targets)

        // Pode estar no lobby morto (início) ou já na tela de substituição (retomada após uma tentativa parcial).
        var page = waitText(5000) { isTeamLobby(it) || isReplacementScreen(it) }
            ?: return if (isPaused()) "Troca pausada" else fail("Troca: ainda não reconheci o lobby nem a tela de substituição")
        if (isTeamLobby(page.second)) {
            val equipe = page.second.find("EQUIPE", yMin = 0.7f)
                ?: return fail("Troca: encontrei o lobby, mas não localizei o botão EQUIPE")
            tapWord(g, page.first, equipe)
            page = waitText(6000) { isReplacementScreen(it) }
                ?: return if (isPaused()) "Troca pausada" else fail("Troca: a tela de escolher personagens não apareceu")
        }

        delay(300)

        // 3) Seleciona os personagens tocando nas cartas; o próprio jogo substitui os espaços com caveira.
        pickAll(g, targets, substitutionMode = true)?.let { return it }

        // 4) Conferência SÓ informativa (uma leitura rápida, sem esperar): se o OCR não leu os nomes da carta pequena, segue mesmo
        //    assim, porque o jogo só deixa confirmar com tudo certo e o passo 6 acusa se não deu. Antes esperava até 1,5 s aqui.
        cap.latest()?.let { b ->
            val s = text.read(b, 0f, 0f, 1f, 0.5f)
            if (!targets.withIndex().all { inSlot(s, it.value, it.index) }) debug(b, "time2_nao_li_quadrados")
        }

        // 5) Só toca quando o botão está habilitado. Desativado, o jogo mostra "É necessário pelo menos um personagem do time";
        // após selecionar os personagens, essa mensagem some e então confirmamos a troca.
        val conf = waitConfirmEnabled(8000)
            ?: return if (isPaused()) "Troca pausada" else fail("Troca: CONFIRMAR ALTERAÇÕES continuou desativado após selecionar os personagens")
        tapWord(g, conf.first, conf.second.find("CONFIRMAR")!!)

        // 6) Volta ao lobby (botão EQUIPE e PARAR E RECEBER lá embaixo). Lê só a faixa de baixo para detectar mais rápido.
        waitText(10000, rBottom) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) }
            ?: return if (isPaused()) "Troca pausada" else fail("Troca: não voltei ao lobby depois de confirmar")
        delay(300)
        secondTeamCommitted = true

        // 7) Equipa Batman, Cavaleiro Arkham e Caçador de Marte (mesma lógica de arrasto, agora pelo nome do equipamento).
        return equipTeam(g, equip2, targets)
    }

    /**
     * Entra na escolha de equipamentos do personagem idx (carta dele no lobby). Algumas vezes o toque na carta abre antes a tela
     * do personagem (tem "EQUIPAMENTO" e os 3 quadrados); nesse caso toca no 1º quadrado de equipamento. Devolve a tela ou null.
     */
    private suspend fun openEquip(g: BotAccessibilityService, idx: Int, who: Target): Pair<Bitmap, Screen>? {
        val isPicker = { s: Screen -> s.has("CLASSIFICADO") || s.has("PONTUACAO") }
        val isDetail = { s: Screen -> s.has("EQUIPAMENTO") && (s.has("MELHORAR") || s.has("SUPORTE")) }
        val isLobby = { s: Screen -> s.has("RECEBER", yMin = 0.7f) && s.has("EQUIPE", yMin = 0.7f) && !s.has("CONFIRMAR") }
        // Olha em que tela está e faz o que falta. Se o toque no centro da carta não abre, tenta pontos alternativos continuamente
        // ponto da carta e guarda um print do que apareceu depois de cada toque (ver debug/equip_tap*): (dx, y, longo?).
        val tapPts = listOf(
            Triple(0f, 0.48f, false), Triple(0f, 0.32f, false), Triple(0f, 0.62f, false), Triple(0f, 0.48f, true),
            Triple(-0.04f, 0.40f, false), Triple(0.04f, 0.40f, false), Triple(0f, 0.56f, true), Triple(0f, 0.40f, false),
        )
        var tries = 0L
        while (!isPaused()) {
            val cur = waitText(4000) { isPicker(it) || isDetail(it) || isLobby(it) }
            if (cur != null) {
                when {
                    isPicker(cur.second) -> return cur
                    isDetail(cur.second) -> {
                        // Quadrados de equipamento (do seu print): x ~0,67 / 0,77 / 0,87 e y ~0,50. O toque no 1º abre a escolha.
                        g.tap(cur.first.width * 0.666f, cur.first.height * 0.50f)
                        waitText(4000) { isPicker(it) }?.let { return it }
                    }
                    else -> {   // lobby: espera a animação acabar e toca na carta do personagem
                        delay(1000)
                        val b0 = cap.latest()
                        if (b0 != null) {
                            if (tries == 0L) debug(b0, "equip_lobby_antes${idx + 1}")   // como o lobby estava antes do 1º toque
                            // 1º: toca no NOME do personagem na carta (achado pelo texto, logo abaixo do meio da carta). Se não
                            // abrir, tenta o nome um pouco acima; depois cai nos pontos fixos da carta. O nome fica embaixo do
                            // subtítulo ("Contenção", "Batman Ninja"), então pega a palavra mais baixa que combina.
                            val sc = text.read(b0)
                            val nameW = sc.words.filter {
                                matches(it, who) && it.cy in 0.5f..0.8f && abs(it.cx - lobbyCardX[idx.coerceIn(0, 2)]) < 0.09f
                            }.maxByOrNull { it.cy }
                            if (nameW != null && tries < 2L) {
                                val yy = nameW.cy - (if (tries == 1L) 0.03f else 0f)
                                g.tap(b0.width * nameW.cx, b0.height * yy)
                            } else {
                                val tapIndex = ((if (nameW != null) tries - 2 else tries) % tapPts.size).toInt()
                                val (dx, ty, long) = tapPts[tapIndex]
                                g.tap(b0.width * (lobbyCardX[idx.coerceIn(0, 2)] + dx), b0.height * ty, if (long) 450L else 40L)
                            }
                            tries++
                            delay(1300)
                            if (tries <= 6L) cap.latest()?.let { debug(it, "equip_tap${idx + 1}_n${tries}") }
                        } else delay(900)
                    }
                }
            }
        }
        cap.latest()?.let { b ->
            debug(b, "equip_nao_abriu")
            // Guarda o que o leitor viu na tela (as palavras maiores) para a mensagem do painel dizer em que tela o bot parou.
            openInfo = text.read(b).words.sortedByDescending { it.w * it.h }.take(8).joinToString(" / ") { it.text }
        }
        return null
    }

    private var openInfo = ""

    /** Sai da seleção de equipamento e, se ela voltar aos detalhes do personagem, toca CANCELAR mais uma vez até chegar ao lobby. */
    private suspend fun exitToLobby(g: BotAccessibilityService): Boolean {
        while (!isPaused()) {
            val b = cap.latest()
            if (b == null) { delay(200); continue }
            val s = text.read(b)
            // O lobby tem CANCELAR também; confirme RECEBER + EQUIPE antes de qualquer toque para não sair dele.
            if (isTeamLobby(s)) return true

            val detail = s.has("EQUIPAMENTO") && s.hasAny("SUPORTE", "MELHORIAS", "NIVEL ATUAL", "CRITICO")
            val back = s.find("CANCELAR", yMin = 0.82f) ?: s.find("VOLTAR", yMin = 0.82f)
            if (back != null) {
                say(if (detail) "Detalhes do personagem: voltando ao lobby..." else "Equipamentos: voltando ao lobby...")
                g.tap(back.cx * b.width, back.cy * b.height)
            } else if (detail) {
                // A palavra pode falhar no OCR, mas a tela de detalhes tem o botão CANCELAR fixo no canto inferior esquerdo.
                say("Botão de voltar não lido nos detalhes; tocando no botão inferior conhecido.")
                g.tap(b.width * 0.14f, b.height * 0.94f)
            } else {
                say("Aguardando o botão de voltar da tela de equipamentos...")
                delay(700)
                continue
            }
            delay(700)
        }
        return false
    }

    /** Confirma em quadros diferentes que os três quadrados estão ocupados e sem equipamento conhecido como errado. */
    private suspend fun confirmEquipmentSlots(list: List<Target>, lay: Layout): Boolean {
        val hits = IntArray(list.size)
        var lastFrame: Bitmap? = null
        var reads = 0
        while (currentCoroutineContext().isActive && !isPaused() && reads < 4) {
            val frame = cap.latestFrame()
            if (frame == null || frame.first === lastFrame) { delay(20); continue }
            lastFrame = frame.first
            reads++
            val s = text.read(frame.first, 0.15f, 0.28f, 0.85f, 0.52f)
            for (i in list.indices) {
                val wrong = wrongItemName(s, list[i], i, lay)
                val matchesExpected = slotHas(s, list[i], i, lay)
                // Alguns nomes pequenos não são lidos pelo OCR; aceita o slot ocupado apenas se não houver equipamento conhecido como errado.
                val acceptable = wrong == null && (matchesExpected || slotOccupied(s, i, lay))
                hits[i] = if (acceptable) (hits[i] + 1).coerceAtMost(2) else (hits[i] - 1).coerceAtLeast(0)
            }
            if (hits.all { it >= 2 }) return true
            delay(50)
        }
        return false
    }

    /**
     * Equipa os 3 personagens do time, um por vez: entra na escolha de equipamentos, preenche os três quadrados, confirma-os e volta ao lobby.
     * Se um quadrado já tem o item certo, não mexe nele. Devolve null se deu certo, ou a mensagem do que falhou.
     */
    private suspend fun equipTeam(g: BotAccessibilityService, loadout: List<List<Target>>, chars: List<Target>): String? {
        for (idx in 0 until 3) {
            var characterDone = false
            var retries = 0
            while (!characterDone && !isPaused()) {
                retries++
                say("Equipando o personagem ${idx + 1} (tentativa $retries)...")
                val picker = openEquip(g, idx, chars[idx])
                if (picker == null) {
                    if (isPaused()) return "Equipamento pausado"
                    say("Não reconheci a tela de equipamentos; vou tentar de novo.")
                    delay(1200)
                    continue
                }
                // Aguarda a tela de equipamentos terminar a transição antes da primeira leitura ou gesto.
                delay(2000)
                var equipped = false
                while (!equipped && !isPaused()) {
                    val error = pickAll(g, loadout[idx], lay = equipLayout)
                    if (error != null) {
                        if (isPaused()) return error
                        say("$error. Vou repetir a equipagem deste personagem.")
                        delay(1000)
                        continue
                    }
                    equipped = confirmEquipmentSlots(loadout[idx], equipLayout)
                    if (!equipped && !isPaused()) {
                        say("Conferindo os equipamentos nos 3 quadrados antes de sair...")
                        delay(200)
                    }
                }
                if (isPaused()) return "Equipamento pausado"
                delay(60)
                cap.latest()?.let { debug(it, "equip_p${idx + 1}_final") }
                if (!exitToLobby(g)) {
                    if (isPaused()) return "Equipamento pausado"
                    say("Não consegui voltar ao lobby; vou tentar novamente.")
                    delay(1000)
                    continue
                }
                delay(800)   // deixa o lobby assentar antes de entrar no próximo personagem
                characterDone = true
            }
            if (isPaused()) return "Equipamento pausado"
        }
        return null
    }
}
