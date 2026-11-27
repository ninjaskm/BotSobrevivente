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
    )

    // Os 3 personagens do time 2. A POSIÇÃO na lista = quadrado de destino (1º, 2º, 3º).
    // "BATMAN" tem que ser exato: "Batman Ninja" aparece como subtítulo em outras cartas.
    private val targets = listOf(
        // Existe mais de um Batman; o do time 2 tem o subtítulo "Batman Ninja" (o outro é "Entre a foice e o mart.").
        Target("Batman (Ninja)", "BATMAN", true, sub = "NINJA", minLevel = 50),
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

    // Arrastar o personagem: segura 0,6 s e move por 0,7 s (a forma que funciona no seu celular). Tem que segurar antes de
    // mover: um arrasto "seco" é lido pelo jogo como rolagem e lança a lista. Não há mais conferência nem tentativas extras.
    private val dragHoldMs = 600L
    private val dragMoveMs = 700L

    /**
     * Quanto tempo cada rodada de arrastos dura antes de parar e analisar de novo (ajustar nos testes). Uma rodada também
     * termina antes se a lista parar de andar (chegou ao fim naquela direção).
     */
    private val phaseMs = 50_000L

    /** Parada (lista solta, sem arrastar) antes de analisar de novo ao fim de cada rodada. */
    private val settleMs = 1200L

    /** As rodadas de arrasto, em ordem: -1 = arrasta para a ESQUERDA (avança na lista), +1 = para a DIREITA (volta). */
    private val phaseDirs = intArrayOf(-1, +1, -1)

    /** Quantas vezes arrasta o mesmo personagem se a carta continuar na lista depois do arrasto. */
    private val maxDragTries = 3

    private fun now() = System.currentTimeMillis()

    private fun matches(wd: Word, t: Target) = if (t.exact) wd.text == t.key else wd.text.contains(t.key)

    /**
     * O personagem está DENTRO do quadrado idx? Olha só a faixa onde o nome aparece na carta do time
     * (y 0,28–0,50, perto do x do quadrado). NÃO exige o subtítulo: na carta pequena do quadrado o OCR quase
     * nunca lê "Contenção"/"Ninja", e exigir isso fazia o bot achar que o arrasto falhou (e tentar de novo,
     * rolando a lista) quando o personagem JÁ estava no quadrado. A versão certa é garantida na hora de escolher
     * a carta na lista (findName), onde o subtítulo é exigido.
     */
    private fun inSlot(s: Screen, t: Target, idx: Int): Boolean =
        s.words.any {
            matches(it, t) && it.cy in 0.28f..0.50f && abs(it.cx - slotX[idx.coerceIn(0, 2)]) < 0.10f
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
            matches(it, t) && it.cy in yMin..yMax &&
                (subOk(s, it, t) || (relaxed && !t.strict && !hasSubtitleAbove(s, it))) &&
                !badSub(s, it, t) && levelOk(s, it, t)
        }.minByOrNull { it.cx }

    /** A carta tem o subtítulo da versão ERRADA logo acima do nome? */
    private fun badSub(s: Screen, name: Word, t: Target): Boolean {
        val bad = t.notSub ?: return false
        return s.words.any { it.text.contains(bad) && abs(it.cx - name.cx) < 0.08f && (name.cy - it.cy) in 0.015f..0.08f }
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
    private suspend fun pickAll(g: BotAccessibilityService, list: List<Target>, emptyStart: Boolean): String? {
        val done = BooleanArray(list.size)
        val attempts = IntArray(list.size)
        var dragged = -1         // último personagem arrastado ainda não confirmado (confirma na próxima leitura)
        var phase = 0            // índice em phaseDirs: 0 = 1ª rodada (esquerda), 1 = direita, 2 = 3ª (esquerda); 3 = acabou
        var phaseStart = -1L     // -1 = rodada ainda não começou: primeiro só analisa a tela, parado
        var steps = 0
        var stuck = 0
        var lastSig: Set<String> = emptySet()
        while (currentCoroutineContext().isActive) {
            val b = cap.latest()
            if (b == null) { delay(200); continue }
            val s = text.read(b)
            for (i in list.indices) {
                if (!done[i] && inSlot(s, list[i], i)) {
                    done[i] = true
                    if (dragged != i) debug(b, "quadrado${i + 1}_ja_cheio")   // diagnóstico: achou o quadrado cheio sem o bot ter arrastado
                }
            }
            val relaxed = phase >= 1   // da 2ª rodada em diante aceita carta com subtítulo ilegível
            // Confere o último arrasto: a carta saiu da lista (está no quadrado) ou continua lá (não pegou: tenta de novo abaixo).
            if (dragged >= 0) {
                val d = dragged
                dragged = -1
                if (!done[d]) {
                    if (findName(s, list[d], yMin = 0.5f, relaxed = relaxed) == null) done[d] = true
                    else if (attempts[d] >= maxDragTries) return fail("Troca: arrastei ${list[d].label} $maxDragTries vezes e a carta continua na lista")
                }
            }
            if (done.all { it }) return null

            val i = list.indices.firstOrNull { !done[it] && findName(s, list[it], yMin = 0.5f, relaxed = relaxed) != null }
            if (i != null) {
                val t = list[i]
                val w = findName(s, t, yMin = 0.5f, relaxed = relaxed)!!
                val sx = w.cx * b.width
                val sy = w.cy * b.height - b.height * 0.08f   // corpo da carta, acima do nome
                val ex = slotX[i.coerceIn(0, 2)] * b.width
                val ey = slotY * b.height
                say("Arrastando ${t.label} para o quadrado ${i + 1}")
                attempts[i]++
                g.drag(sx, sy, ex, ey, dragHoldMs, dragMoveMs)
                delay(700)   // deixa a carta assentar no quadrado
                dragged = i  // confirma na próxima leitura (a carta tem que ter saído da lista)
                // A lista mudou (a carta saiu): zera a comparação de "parou de andar", mas continua na mesma rodada e direção.
                if (phaseStart >= 0) phaseStart = now()
                steps = 0; stuck = 0; lastSig = emptySet()
                continue
            }

            // Nenhum dos que faltam está visível (análise parada).
            val falta = list.filterIndexed { k, _ -> !done[k] }.joinToString { it.label }
            if (phase >= phaseDirs.size) return fail("Troca: não achei na lista depois de ${phaseDirs.size} rodadas: $falta")
            if (phaseStart < 0) phaseStart = now()   // começa a rodada de arrastos
            steps++
            val sig = listSig(s)
            if (sameList(sig, lastSig)) stuck++ else stuck = 0
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
            // ~1,3 carta por passo (cada carta ocupa ~0,14 da largura; a lista mostra ~6) e solta parado: não pula nenhuma.
            val y = b.height * 0.74f
            if (dir > 0) g.scrollStop(b.width * 0.46f, y, b.width * 0.64f, y, 350, 200)
            else g.scrollStop(b.width * 0.64f, y, b.width * 0.46f, y, 350, 200)
            delay(450)   // espera a lista assentar antes de ler
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
        recordEquipScreen(g)   // equipar de verdade = próxima etapa; por ora só grava quadros
        return null
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
        val vis = waitText(2500) { s -> targets.withIndex().all { inSlot(s, it.value, it.index) } }
        if (vis == null) cap.latest()?.let { debug(it, "time2_nao_li_quadrados") }

        // 5) CONFIRMAR ALTERAÇÕES.
        val conf = waitText(3000) { it.has("CONFIRMAR") }
            ?: return fail("Troca: não achei CONFIRMAR ALTERAÇÕES")
        tapWord(g, conf.first, conf.second.find("CONFIRMAR")!!)

        // 6) Volta ao lobby (com botão EQUIPE e PARAR E RECEBER, sem o botão CONFIRMAR).
        waitText(10000) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) && !it.has("CONFIRMAR") }
            ?: return fail("Troca: não voltei ao lobby depois de confirmar")
        delay(800)

        // 7) Só grava quadros da tela de equipamentos do 1º personagem.
        recordEquipScreen(g)
        return null
    }

    private suspend fun recordEquipScreen(g: BotAccessibilityService) {
        val b0 = cap.latest() ?: return
        g.tap(b0.width * 0.1435f, b0.height * 0.48f)   // carta do 1º personagem no lobby
        // 1º passo: abre a tela do personagem (tem "EQUIPAMENTO" e os 3 quadrados de equipamento à direita).
        var eq = waitText(4000) { it.has("CLASSIFICADO") || it.has("PONTUACAO") }
        if (eq == null) {
            val det = waitText(3000) { it.has("EQUIPAMENTO") && (it.has("MELHORAR") || it.has("SUPORTE")) }
            if (det == null) { cap.latest()?.let { debug(it, "equip_nao_abriu") }; return }
            // 2º passo: toca no 1º quadrado de equipamento (vazio ou já equipado: o toque é o mesmo). Do seu print:
            // quadrados em x ~0,67 / 0,77 / 0,87 e y ~0,50.
            g.tap(det.first.width * 0.666f, det.first.height * 0.50f)
            eq = waitText(4000) { it.has("CLASSIFICADO") || it.has("PONTUACAO") }
        }
        if (eq == null) { cap.latest()?.let { debug(it, "equip_nao_abriu") }; return }
        debug(eq.first, "equip_0")
        // Rola a lista de baixo duas vezes e grava, para eu ver o que aparece.
        repeat(2) { i ->
            g.scrollStop(eq.first.width * 0.70f, eq.first.height * 0.74f, eq.first.width * 0.30f, eq.first.height * 0.74f, 600, 250)
            delay(700)
            cap.latest()?.let { debug(it, "equip_${i + 1}") }
        }
        // Sai: na escolha de equipamentos o botão é CANCELAR; na tela do personagem é VOLTAR. Até 3 toques até o lobby.
        repeat(3) {
            val bk = waitText(2500) { it.has("CANCELAR", yMin = 0.85f) || it.has("VOLTAR", yMin = 0.85f) } ?: return
            val wd = bk.second.find("CANCELAR", yMin = 0.85f) ?: bk.second.find("VOLTAR", yMin = 0.85f)!!
            tapWord(g, bk.first, wd)
            if (waitText(3000) { it.has("RECEBER", yMin = 0.7f) && it.has("EQUIPE", yMin = 0.7f) } != null) return
        }
    }
}
