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
 *  - run: troca do time 1 (morto) para o time 2 (EQUIPE -> 3 personagens -> CONFIRMAR ALTERAÇÕES -> lobby).
 *
 * Cada passo confere o texto da tela antes de agir. Qualquer coisa diferente do esperado: salva um print
 * em debug/ e devolve a mensagem de erro (o Brain pausa).
 */
class TeamSwap(
    private val cap: Capture,
    private val text: TextVision,
    private val debug: (Bitmap, String) -> Unit,
) {
    /** Mensagens de progresso para o painel (o Brain liga isto ao texto do painel). */
    var say: (String) -> Unit = {}

    // `sub`: subtítulo que a carta CERTA tem (linha pequena em cima do nome). `notSub`: subtítulo que a carta ERRADA tem.
    // `minLevel`: nível mínimo da carta certa (número no canto superior direito da carta na lista), para separar versões.
    // `strict`: nunca aceita a carta com o subtítulo ilegível (para quando a versão ERRADA não tem subtítulo nenhum).
    // `sub` aceita alternativas separadas por "|" (basta ler uma delas).
    private class Target(
        val label: String, val key: String, val exact: Boolean,
        val sub: String? = null, val notSub: String? = null, val minLevel: Int = 0, val strict: Boolean = false,
        /** Palavra EXTRA (de outra parte do nome) que também vale ao procurar o item DENTRO do quadrado, quando o OCR não lê a chave. */
        val alt: String? = null,
        /**
         * Palavras EXTRAS (separadas por "|") que também valem ao procurar o item na LISTA de baixo, para quando o OCR não lê a chave
         * (nome sobre arte brilhante). Só para palavras que não existem em nenhum outro item da lista.
         */
        val listAlt: String? = null,
    )

    // Os 3 personagens do time 2. A POSIÇÃO na lista = quadrado de destino (1º, 2º, 3º).
    // "BATMAN" tem que ser exato: "Batman Ninja" aparece como subtítulo em outras cartas.
    private val targets = listOf(
        // Existe mais de um Batman; o do time 2 tem o subtítulo "Batman Ninja" (o outro é "Entre a foice e o mart.").
        // `strict`: nunca aceita o Batman com o subtítulo ilegível (o errado TEM subtítulo, o OCR só não leu: na 2ª rodada em diante ele
        // era aceito como "sem subtítulo"). `notSub`: rejeita de vez se ler o subtítulo do errado ("FOICE"/"MART").
        Target("Batman (Ninja)", "BATMAN", true, sub = "NINJA", notSub = "FOICE|MART", minLevel = 50, strict = true),
        Target("Cavaleiro Arkham", "CAVALEIRO", false, minLevel = 50),
        // Existe mais de um Caçador de Marte: o certo é "Noite Mais Densa" nível 50 (o outro é nível 45, sem subtítulo).
        // A chave é "DE MARTE" (não "CACADOR"): o Lobo tem o subtítulo "Caçador de Recompensas" e era confundido com ele.
        // ("DE MARTE" e não só "MARTE", para não casar com "martelo".)
        Target("Caçador de Marte", "DE MARTE", false, sub = "NOITE|DENSA", minLevel = 50, strict = true),
    )

    // Os 3 do time 1, na ordem dos quadrados (foto de referência: Flash Reverso, Apocalipse, Nevasca).
    private val team1 = listOf(
        Target("Flash Reverso", "REVERSO", false, minLevel = 30),
        // Existe mais de um Apocalipse; o certo tem o subtítulo "Contenção" (o outro é "Noite Mais Densa").
        Target("Apocalipse (Contenção)", "APOCALIPSE", false, sub = "CONTEN", minLevel = 40),
        // Existe mais de uma Nevasca: a errada tem o subtítulo "Regime" e nível 3; a certa é nível 50, sem subtítulo.
        Target("Nevasca", "NEVASCA", false, notSub = "REGIME", minLevel = 40),
    )

    // Centro dos 3 quadrados do time, em proporção da tela (dos seus prints).
    private val slotX = floatArrayOf(0.1375f, 0.306f, 0.476f)
    private val slotY = 0.23f

    /** Onde ficam as coisas em cada tela de arrastar (proporção da tela, dos seus prints). */
    private class Layout(
        val slotX: FloatArray, val slotY: Float,
        /** Faixa de altura e distância horizontal em que o NOME do item aparece dentro do quadrado já preenchido. */
        val slotBand: ClosedFloatingPointRange<Float>, val slotDx: Float,
        /** Altura mínima da lista de baixo (nomes acima disso são do quadrado, não da lista). */
        val stripMinY: Float,
        /** Quanto acima do nome fica o corpo da carta (de onde se pega o item). */
        val srcDy: Float,
        /** Rolagem da lista: pega em `scrollFrom` e solta em `scrollTo` (largura da tela) = avança ~4 cartas por passo (personagens e equipamentos). */
        val scrollFrom: Float, val scrollTo: Float,
        /** true = o item CONTINUA na lista depois de equipado (equipamento); false = a carta sai da lista (personagem). */
        val staysInList: Boolean,
    )
    private val charLayout = Layout(slotX, slotY, 0.28f..0.50f, 0.10f, 0.50f, 0.08f, 0.78f, 0.23f, false)
    private val equipLayout = Layout(floatArrayOf(0.328f, 0.494f, 0.658f), 0.32f, 0.37f..0.47f, 0.09f, 0.62f, 0.09f, 0.765f, 0.293f, true)

    // Equipamentos de cada personagem, na ordem dos quadrados 1, 2, 3 (dos seus prints de equipamentos).
    // A chave é uma palavra da linha de cima do nome (os nomes longos quebram em 2 linhas) que não existe em outro equipamento.
    // Palavra alternativa única de cada item (da 2ª linha do nome, que o OCR lê quando erra a 1ª): só serve para ver se o item JÁ
    // está no quadrado. Placa/Maça/Traje não têm (as palavras deles repetem em outros itens).
    private val gearAlt = mapOf(
        "ARMADURA" to "LEXCORP", "RIFLE" to "PERSONALIZADO", "KAMA" to "REINO", "BASTOES" to "ESCRIMA",
        "MANHA" to "ENESIMO", "FACAS" to "ADEPTO", "DIVINO" to "CAPACETE",
    )
    private fun gear(label: String, key: String) = Target(label, key, false, alt = gearAlt[key], listAlt = gearListAlt[key])
    // Os Bastões de Escrima têm arte azul muito brilhante e o OCR não lê "Bastões": "Escrima"/"Energizados" (2ª linha) são únicos.
    private val gearListAlt = mapOf("BASTOES" to "ESCRIMA|ENERGIZADOS")
    private val equip2 = listOf(
        listOf(gear("Placa Peitoral Divina do Quarto Mundo", "PLACA"), gear("Maça Divina do Quarto Mundo", "MACA"),
            gear("Facas de Adepto da Liga dos Assassinos", "FACAS")),                       // Batman
        listOf(gear("Rifle Personalizado", "RIFLE"), gear("Kama do Reino Inferior", "KAMA"),
            gear("Estrela da Manhã de Metal Enésimo", "MANHA")),                              // Cavaleiro Arkham
        listOf(gear("Bastões de Escrima Energizados", "BASTOES"), gear("Armadura Peitoral LexCorp V1", "ARMADURA"),
            gear("Traje do Caçador de Marte", "CACADOR")),                                 // Caçador de Marte
    )
    private val equip1 = listOf(
        // Dos prints de 27/11 (quadrados 1, 2, 3). "Traje de Hipervelocidade" existe em 2 versões (3★ e Revestido): a chave é "REVESTIDO".
        listOf(gear("Rifle Personalizado", "RIFLE"), gear("Armadura Peitoral LexCorp V1", "ARMADURA"),
            gear("Traje de Hipervelocidade Revestido", "REVESTIDO")),                       // Flash Reverso
        listOf(gear("Bastões de Escrima Energizados", "BASTOES"), gear("Estrela da Manhã de Metal Enésimo", "MANHA"),
            gear("Kama do Reino Inferior", "KAMA")),                                        // Apocalipse
        listOf(gear("Placa Peitoral Divina do Quarto Mundo", "PLACA"), gear("Maça Divina do Quarto Mundo", "MACA"),
            gear("Capacete Divino do Quarto Mundo", "DIVINO")),                             // Nevasca
    )

    // Centro da carta de cada personagem no lobby (para entrar na tela de equipamentos dele).
    private val lobbyCardX = floatArrayOf(0.1435f, 0.305f, 0.466f)

    // Arrastar o personagem/item: segura 0,9 s PARADO em cima da carta e só então move por 0,7 s. Tem que segurar bastante antes
    // de mover: um arrasto "seco" (ou com pouca espera) é lido pelo jogo como rolagem e lança a lista. Era 0,6 s e ainda aparecia
    // um arrasto de rolagem de vez em quando.
    private val dragHoldMs = 900L
    private val dragMoveMs = 700L

    // Rolagem da lista de PERSONAGENS: 3,5x mais rápida que a dos equipamentos (350 ms de movimento + 200 ms parado no fim + 250 ms de espera
    // antes de ler viraram 100 + 57 + 70). Os equipamentos continuam com os tempos antigos.
    private val charScrollMoveMs = (350 / 3.5).toLong()
    private val charScrollHoldMs = (200 / 3.5).toLong()
    private val charSettleMs = (250 / 3.5).toLong()

    /**
     * Quanto tempo cada rodada de arrastos dura antes de parar e analisar de novo (ajustar nos testes). Uma rodada também
     * termina antes se a lista parar de andar (chegou ao fim naquela direção).
     */
    private val phaseMs = 50_000L

    /** Parada (lista solta, sem arrastar) antes de analisar de novo ao fim de cada rodada. */
    private val settleMs = 500L

    /** As rodadas de arrasto, em ordem: -1 = arrasta para a ESQUERDA (avança na lista), +1 = para a DIREITA (volta). */
    private val phaseDirs = intArrayOf(-1, +1, -1)

    /** Quantas vezes arrasta o mesmo personagem se a carta continuar na lista depois do arrasto. */
    private val maxDragTries = 3

    /**
     * Fração do passo de rolagem: 1 = passo cheio (~4 cartas; sobram 2 a 3 de sobreposição para nenhuma ser pulada). Cai para 0,4
     * (~1,6 carta) se um passo andar demais (ver a segurança em pickAll). Uma para os personagens e outra para os equipamentos.
     */
    @Volatile private var charStepScale = 1f
    @Volatile private var equipStepScale = 1f

    private fun now() = System.currentTimeMillis()

    /** Textos de botão de aceitar de uma janela de confirmação depois de arrastar um equipamento (palpite; o print mostra o real). */
    private val dialogOk = setOf("SIM", "CONFIRMAR", "EQUIPAR", "TROCAR", "MOVER", "SUBSTITUIR", "OK")

    private fun matches(wd: Word, t: Target) = if (t.exact) wd.text == t.key else wd.text.contains(t.key)

    private fun altMatch(wd: Word, t: Target) = t.alt != null && wd.text.contains(t.alt)

    /** Chave do alvo OU uma das palavras extras de lista (`listAlt`). Só usado ao procurar o item na lista de baixo. */
    private fun matchesInList(wd: Word, t: Target): Boolean =
        matches(wd, t) || (t.listAlt != null && t.listAlt.split('|').any { wd.text.contains(it) })

    /**
     * Leitura reforçada para a lista de EQUIPAMENTOS: letras pequenas sobre arte brilhante. Além do quadro inteiro, lê a lista de
     * baixo em duas metades (cada uma com ~2x a resolução que o OCR teria no quadro inteiro) e junta tudo.
     */
    private suspend fun readBoosted(b: Bitmap): Screen {
        val full = text.read(b)
        val left = text.read(b, 0f, 0.58f, 0.56f, 0.97f)
        val right = text.read(b, 0.44f, 0.58f, 1f, 0.97f)
        return Screen(full.words + left.words + right.words)
    }

    /**
     * Leitura só da LISTA de equipamentos (as duas metades, sem o quadro inteiro): ~40% mais leve que `readBoosted`. Usada enquanto
     * só se rola a lista; nas paradas para analisar (que olham os quadrados) continua a leitura completa.
     */
    private suspend fun readListBoosted(b: Bitmap): Screen {
        val left = text.read(b, 0f, 0.58f, 0.56f, 0.97f)
        val right = text.read(b, 0.44f, 0.58f, 1f, 0.97f)
        return Screen(left.words + right.words)
    }

    /** O item (pela chave ou pela palavra alternativa) aparece em QUALQUER um dos 3 quadrados? (tela de equipamentos) */
    private fun slotHas(s: Screen, t: Target, lay: Layout): Boolean =
        s.words.any { (matches(it, t) || altMatch(it, t)) && it.cy in lay.slotBand && it.cx in 0.15f..0.85f }

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
     *  3) Acabou o tempo (ou a lista parou de andar): PARA, espera `settleMs` e analisa de novo, parado. Achou: arrasta.
     *     Não achou: 2ª rodada, para a DIREITA. Mesmo fim: para, analisa; 3ª rodada, para a ESQUERDA (`phaseDirs`).
     *  4) Acabou a 3ª rodada: para, analisa uma última vez e, se não achou, desiste com mensagem.
     * Depois de arrastar um personagem: solta, volta a ANALISAR (parado). Se a carta ainda aparece na lista, o arrasto não pegou e
     * arrasta de novo (até `maxDragTries`); se saiu da lista, está no quadrado. Se ainda há outro personagem à vista, arrasta também;
     * se não, CONTINUA rolando na mesma direção e rodada de onde estava (não volta ao início nem recomeça as rodadas).
     * `emptyStart`: não muda mais nada aqui. Antes ele usava a cor do quadrado (`slotFilled`) para achar quadrado já cheio,
     * mas o painel do bot fica em cima do quadrado 1 e a captura o inclui, então o quadrado 1 parecia sempre cheio.
     * Devolve null se os 3 foram arrastados, ou a mensagem do que falhou.
     */
    private suspend fun pickAll(
        g: BotAccessibilityService, list: List<Target>, emptyStart: Boolean, lay: Layout = charLayout,
    ): String? {
        val done = BooleanArray(list.size)
        val attempts = IntArray(list.size)
        var dragged = -1         // último personagem arrastado ainda não confirmado (confirma na próxima leitura)
        var phase = 0            // índice em phaseDirs: 0 = 1ª rodada (esquerda), 1 = direita, 2 = 3ª (esquerda); 3 = acabou
        var phaseStart = -1L     // -1 = rodada ainda não começou: primeiro só analisa a tela, parado
        var steps = 0
        var stuck = 0
        var offPicker = 0
        var confirmMiss = 0      // leituras seguidas em que a confirmação do nome (antes de arrastar) não bateu
        var lastSig: Set<String> = emptySet()
        while (currentCoroutineContext().isActive) {
            val b = cap.latest()
            if (b == null) { delay(200); continue }
            // Enquanto só está ROLANDO a lista, lê apenas a faixa de baixo (a lista): personagens, ~40% menos pixels; equipamentos, sem o
            // quadro inteiro. Nas paradas para analisar (início de rodada, depois de arrastar, fim das rodadas) lê a tela toda (quadrados).
            val rolling = phaseStart >= 0 && dragged < 0
            val s = when {
                lay.staysInList && rolling -> readListBoosted(b)
                lay.staysInList -> readBoosted(b)
                rolling -> text.read(b, 0f, 0.40f, 1f, 1f)
                else -> text.read(b)
            }
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
                        say("Quadrado ${k + 1} tem item errado ($wr): vou trocar por ${list[k].label}")
                    }
                    if (attempts[k] >= 8) return fail("Equipar: o quadrado ${k + 1} continua com item errado ($wr) depois de ${attempts[k]} tentativas")
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
                // Se a tela deixou de ser a de escolher equipamento (um gesto abriu outra tela), espera um pouco e, se continuar, para.
                if (s.has("CLASSIFICADO") || s.has("PONTUACAO")) offPicker = 0
                else {
                    offPicker++
                    if (offPicker >= 3) {
                        debug(b, "equip_tela_outra")
                        return fail("Equipar: depois do gesto abriu outra tela (veja debug/equip_tela_outra e equip_tentativa*)")
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
                        if (attempts[d] >= 8) return fail("Equipar: o quadrado ${d + 1} continua com item errado ($wrong) depois de ${attempts[d]} tentativas")
                    } else if (lay.staysInList) {
                        // Equipamento equipado por ESTE personagem some da lista dele (visto nas fotos); o de outro personagem continua lá até ser roubado.
                        // Se o OCR não leu o nome no quadrado, arrasta mais 1 vez e depois aceita, guardando um print para você conferir.
                        if (findName(s, list[d], yMin = lay.stripMinY, relaxed = relaxed) == null) done[d] = true   // saiu da lista = está no quadrado
                        else if (slotOccupied(s, d, lay)) {
                            // A carta continua na lista mas o quadrado tem item: provavelmente o OCR não liberou a leitura. Aceita após 2 arrastos.
                            if (attempts[d] >= 2) { done[d] = true; debug(b, "equip_sem_confirmar") }
                        } else if (attempts[d] >= 4) {
                            // Quadrado VAZIO e a carta ainda na lista depois dos 4 gestos: não equipou de verdade. Falha com aviso
                            // (antes seguia em silêncio e o quadrado ficava vazio).
                            debug(b, "equip_falhou")
                            return fail("Equipar: tentei 4 gestos com ${list[d].label} e o quadrado ${d + 1} continua vazio (item em uso por outro personagem? veja debug/equip_tentativa*)")
                        }
                    } else if (findName(s, list[d], yMin = lay.stripMinY, relaxed = relaxed) == null) done[d] = true
                    else if (attempts[d] >= maxDragTries) return fail("Troca: arrastei ${list[d].label} $maxDragTries vezes e a carta continua na lista")
                }
            }
            if (done.all { it }) return null

            val i = list.indices.firstOrNull { !done[it] && findName(s, list[it], yMin = lay.stripMinY, relaxed = relaxed) != null }
            if (i != null) {
                val t = list[i]
                val w0 = findName(s, t, yMin = lay.stripMinY, relaxed = relaxed)!!
                // Personagens: ANTES de arrastar confirma o NOME num quadro NOVO (a leitura de cima pode ser de uns 0,3 s atrás e a lista
                // pode ter mexido). Se o personagem certo não está mais ali (ou andou), não arrasta: volta a analisar. Usa a posição
                // nova. Depois de 3 conferências seguidas sem bater, arrasta mesmo assim com a leitura de cima (já tem versão e nível conferidos).
                val w = if (lay.staysInList) w0 else {
                    val b2 = cap.latest()
                    val s2 = if (b2 != null) text.read(b2, 0f, 0.40f, 1f, 1f) else null
                    val w2 = if (s2 != null) findName(s2, t, yMin = lay.stripMinY, relaxed = relaxed) else null
                    if (w2 != null && abs(w2.cx - w0.cx) <= 0.03f && abs(w2.cy - w0.cy) <= 0.03f) {
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
                    // Equipamento: ainda não sabemos qual gesto o jogo aceita (e o item pode estar em uso por outro personagem), então cada
                    // tentativa usa um gesto diferente e guarda um print do que o jogo fez (debug/equip_tentativa*).
                    val v = (attempts[i] - 1) % 4
                    say("Equipando ${t.label} no quadrado ${i + 1} (gesto ${v + 1} de 4)")
                    when (v) {
                        0 -> g.drag(sx, sy, ex, ey, dragHoldMs, dragMoveMs)                       // segura e arrasta direto
                        // sobe primeiro, saindo da faixa da lista (não lê como rolagem), depois vai ao quadrado
                        1 -> g.dragVia(sx, sy, sx, b.height * 0.52f, ex, ey, dragHoldMs, 400L, 500L)
                        2 -> { g.tap(sx, sy); delay(800); g.tap(ex, ey) }                         // toca no item e depois no quadrado
                        else -> { g.tap(ex, ey); delay(800); g.tap(sx, sy) }                      // toca no quadrado e depois no item
                    }
                    delay(900)
                    cap.latest()?.let { debug(it, "equip_tentativa${attempts[i]}_${t.key}") }
                } else {
                    // Segura PARADO em cima da carta (dragHoldMs, sem mexer o dedo), e SEM soltar sobe reto para fora da faixa da lista
                    // (assim o jogo não lê como rolagem lateral) e só depois vai até o quadrado, onde fica parado antes de soltar.
                    g.dragVia(sx, sy, sx, b.height * 0.40f, ex, ey, dragHoldMs, 350L, 450L)
                }
                delay(if (lay.staysInList) 700L else 400L)   // deixa a carta assentar no quadrado
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
                    if (!done[k] && slotHas(s, list[k], lay)) { done[k] = true; debug(b, "equip_${k + 1}_ja_equipado") }
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
                return fail("Troca: não achei na lista depois de ${phaseDirs.size} rodadas: ${list.filterIndexed { k, _ -> !done[k] }.joinToString { it.label }}")
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
                // Fim da rodada (tempo ou fim da lista): PARA, espera a lista assentar e volta a analisar.
                val dirTxt = if (phaseDirs[phase] > 0) "direita" else "esquerda"
                phase++
                phaseStart = -1L; steps = 0; stuck = 0; lastSig = emptySet()
                say("Parei de arrastar para a $dirTxt sem achar $falta. Analisando de novo...")
                delay(settleMs)
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
            val mvMs = if (lay.staysInList) 350L else charScrollMoveMs
            val hdMs = if (lay.staysInList) 200L else charScrollHoldMs
            if (dir > 0) g.scrollStop(toX, y, fromX, y, mvMs, hdMs)
            else g.scrollStop(fromX, y, toX, y, mvMs, hdMs)
            delay(if (lay.staysInList) 300L else charSettleMs)   // espera a lista assentar antes de ler
        }
        return "Troca cancelada"
    }

    /**
     * Tela "CRIE SUA EQUIPE" (time 1 vazio): arrasta Flash Reverso, Apocalipse e Nevasca para os quadrados 1, 2 e 3,
     * toca no botão CRIE SUA EQUIPE, espera o lobby e grava quadros da tela de equipamentos do 1º personagem.
     * Devolve null se deu certo ou a mensagem do que falhou.
     */
    suspend fun createTeam1(g: BotAccessibilityService): String? {
        pickAll(g, team1, emptyStart = true)?.let { return it }
        // Conferência só informativa: o jogo não deixa criar a equipe sem os 3 quadrados cheios, então se algo estiver
        // errado o lobby não aparece e o erro sai logo abaixo (com print em debug/).
        val vis = waitText(2500) { s -> team1.withIndex().all { inSlot(s, it.value, it.index) } }
        if (vis == null) cap.latest()?.let { debug(it, "time1_nao_li_quadrados") }
        // O botão fica no meio da tela (y ~0,33); o título "CRIE SUA EQUIPE" está colado no topo e não conta.
        // Toca bem em cima da palavra "CRIE" (lado esquerdo do botão, x ~0,65 / y ~0,335 no seu print). O x é fixo
        // porque o leitor de texto pode devolver a linha inteira e o centro dela cairia mais para a direita.
        // Se a tela não mudar em ~4 s, toca de novo (até 3 vezes) antes de desistir.
        var lobbyOk = false
        for (tentativa in 0 until 3) {
            val b = cap.latest() ?: return fail("Time 1: não achei o botão CRIE SUA EQUIPE")
            val s = text.read(b)
            val y = first(s, "CRIE", false, 0.2f, 0.55f)?.cy ?: first(s, "EQUIPE", false, 0.2f, 0.55f)?.cy ?: 0.335f
            g.tap(b.width * 0.65f, b.height * y)
            val r = waitText(4000) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) && !it.has("CONFIRMAR") }
            if (r != null) { lobbyOk = true; break }
        }
        if (!lobbyOk) return fail("Time 1: depois do botão não apareceu o lobby (veja debug/troca_falha)")
        delay(800)
        return equipTeam(g, equip1, team1)   // equipa Flash Reverso, Apocalipse e Nevasca (mesma lógica de arrasto, agora pelo nome do equipamento)
    }

    private suspend fun waitText(timeoutMs: Long, pred: (Screen) -> Boolean): Pair<Bitmap, Screen>? {
        val end = now() + timeoutMs
        while (currentCoroutineContext().isActive && now() < end) {
            val b = cap.latest()
            if (b != null) {
                val s = text.read(b)
                if (pred(s)) return b to s
            }
            delay(200)
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
        // 1) Lobby do time morto: toca em EQUIPE.
        val lobby = waitText(4000) { it.has("EQUIPE", yMin = 0.7f) }
            ?: return fail("Troca: não achei o botão EQUIPE")
        tapWord(g, lobby.first, lobby.second.find("EQUIPE", yMin = 0.7f)!!)

        // 2) Tela de escolha de personagens.
        waitText(6000) { it.has("CLASSIFICAR") || it.has("SUBSTITUICOES") || it.has("CONFIRMAR") }
            ?: return fail("Troca: a tela de escolher personagens não apareceu")
        delay(500)

        // 3) Arrasta os 3 para os quadrados (quadro 1, 2, 3).
        pickAll(g, targets, emptyStart = false)?.let { return it }

        // 4) Confere (só informativo) se os 3 estão nos quadrados antes de confirmar. Se o OCR não leu os nomes da
        //    carta pequena, segue mesmo assim: o jogo só deixa confirmar com tudo certo e o passo 6 acusa se não deu.
        val vis = waitText(1500) { s -> targets.withIndex().all { inSlot(s, it.value, it.index) } }
        if (vis == null) cap.latest()?.let { debug(it, "time2_nao_li_quadrados") }

        // 5) CONFIRMAR ALTERAÇÕES.
        val conf = waitText(3000) { it.has("CONFIRMAR") }
            ?: return fail("Troca: não achei CONFIRMAR ALTERAÇÕES")
        tapWord(g, conf.first, conf.second.find("CONFIRMAR")!!)

        // 6) Volta ao lobby (com botão EQUIPE e PARAR E RECEBER, sem o botão CONFIRMAR).
        waitText(10000) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) && !it.has("CONFIRMAR") }
            ?: return fail("Troca: não voltei ao lobby depois de confirmar")
        delay(800)

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
        // Até 8 voltas: olha em que tela está e faz o que falta. Se o toque no centro da carta não abre, a cada volta tenta outro
        // ponto da carta e guarda um print do que apareceu depois de cada toque (ver debug/equip_tap*): (dx, y, longo?).
        val tapPts = listOf(
            Triple(0f, 0.48f, false), Triple(0f, 0.32f, false), Triple(0f, 0.62f, false), Triple(0f, 0.48f, true),
            Triple(-0.04f, 0.40f, false), Triple(0.04f, 0.40f, false), Triple(0f, 0.56f, true), Triple(0f, 0.40f, false),
        )
        var tries = 0
        repeat(8) {
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
                            if (tries == 0) debug(b0, "equip_lobby_antes${idx + 1}")   // como o lobby estava antes do 1º toque
                            // 1º: toca no NOME do personagem na carta (achado pelo texto, logo abaixo do meio da carta). Se não
                            // abrir, tenta o nome um pouco acima; depois cai nos pontos fixos da carta. O nome fica embaixo do
                            // subtítulo ("Contenção", "Batman Ninja"), então pega a palavra mais baixa que combina.
                            val sc = text.read(b0)
                            val nameW = sc.words.filter {
                                matches(it, who) && it.cy in 0.5f..0.8f && abs(it.cx - lobbyCardX[idx.coerceIn(0, 2)]) < 0.09f
                            }.maxByOrNull { it.cy }
                            if (nameW != null && tries < 2) {
                                val yy = nameW.cy - (if (tries == 1) 0.03f else 0f)
                                g.tap(b0.width * nameW.cx, b0.height * yy)
                            } else {
                                val (dx, ty, long) = tapPts[(if (nameW != null) tries - 2 else tries) % tapPts.size]
                                g.tap(b0.width * (lobbyCardX[idx.coerceIn(0, 2)] + dx), b0.height * ty, if (long) 450L else 40L)
                            }
                            tries++
                            delay(1300)
                            if (tries <= 6) cap.latest()?.let { debug(it, "equip_tap${idx + 1}_n${tries}") }
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

    /** Sai: na escolha de equipamentos o botão é CANCELAR/VOLTAR; na tela do personagem é VOLTAR. Até 3 toques até o lobby. */
    private suspend fun exitToLobby(g: BotAccessibilityService) {
        repeat(3) {
            // O lobby também tem um botão CANCELAR no canto (visto no seu print): se já estamos no lobby, NÃO toca nele.
            val atual = cap.latest()?.let { text.read(it) }
            if (atual != null && atual.has("RECEBER", yMin = 0.7f) && atual.has("EQUIPE", yMin = 0.7f) && !atual.has("CONFIRMAR")) return
            val bk = waitText(2500) { it.has("CANCELAR", yMin = 0.85f) || it.has("VOLTAR", yMin = 0.85f) } ?: return
            val wd = bk.second.find("CANCELAR", yMin = 0.85f) ?: bk.second.find("VOLTAR", yMin = 0.85f)!!
            tapWord(g, bk.first, wd)
            if (waitText(3000) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) } != null) return
        }
    }

    /**
     * Equipa os 3 personagens do time, um por vez: entra na escolha de equipamentos, arrasta os 3 itens para os quadrados 1, 2 e 3
     * (mesma lógica do arrasto de personagens: acha pelo nome, rola a lista, segura e arrasta) e sai de volta ao lobby.
     * Se um quadrado já tem o item certo (nome lido no quadrado), não mexe nele.
     * Devolve null se deu certo, ou a mensagem do que falhou.
     */
    private suspend fun equipTeam(g: BotAccessibilityService, loadout: List<List<Target>>, chars: List<Target>): String? {
        for (idx in 0 until 3) {
            say("Equipando o personagem ${idx + 1}...")
            openEquip(g, idx, chars[idx]) ?: return fail("Equipar: não abri a tela de equipamentos do personagem ${idx + 1}. Vi: $openInfo")
            delay(500)
            pickAll(g, loadout[idx], emptyStart = false, lay = equipLayout)?.let { return it }
            delay(500)
            cap.latest()?.let { debug(it, "equip_p${idx + 1}_final") }   // para você conferir como ficou
            exitToLobby(g)
            delay(1200)   // deixa o lobby assentar antes de entrar no próximo personagem
        }
        return null
    }
}
