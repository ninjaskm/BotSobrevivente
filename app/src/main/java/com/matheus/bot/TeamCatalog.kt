package com.matheus.bot

/** Descrição de um personagem ou equipamento procurado na montagem dos times. */
internal class Target(
    val label: String,
    val key: String,
    val exact: Boolean,
    val sub: String? = null,
    val notSub: String? = null,
    val minLevel: Int = 0,
    val strict: Boolean = false,
    val alt: String? = null,
    val listAlt: String? = null,
)

/** Geometria e comportamento da lista/tela de destino durante uma busca. */
internal class Layout(
    val slotX: FloatArray,
    val slotY: Float,
    val slotBand: ClosedFloatingPointRange<Float>,
    val slotDx: Float,
    val stripMinY: Float,
    val srcDy: Float,
    val scrollFrom: Float,
    val scrollTo: Float,
    val staysInList: Boolean,
)

/** Dados fixos do projeto separados dos algoritmos OCR, busca e gesto de [TeamSwap]. */
internal object TeamCatalog {
    // Batman é exato para não casar "Batman Ninja" usado como subtítulo em outra carta.
    // O Caçador usa "DE MARTE" para não casar com o Lobo e exige a versão nível 50.
    val targets = listOf(
        Target("Batman (Ninja)", "BATMAN", true, sub = "NINJA", notSub = "FOICE|MART", minLevel = 50, strict = true),
        Target("Cavaleiro Arkham", "CAVALEIRO", false, minLevel = 50),
        Target("Caçador de Marte", "DE MARTE", false, sub = "NOITE|DENSA", minLevel = 50, strict = true),
    )

    val team1 = listOf(
        Target("Flash Reverso", "REVERSO", false, minLevel = 30),
        Target("Apocalipse (Contenção)", "APOCALIPSE", false, sub = "CONTEN", minLevel = 40),
        Target("Nevasca", "NEVASCA", false, notSub = "REGIME", minLevel = 40),
    )

    val characterSlotX = floatArrayOf(0.1375f, 0.306f, 0.476f)
    const val characterSlotY = 0.23f

    val characterLayout = Layout(characterSlotX, characterSlotY, 0.28f..0.50f, 0.10f, 0.50f, 0.08f, 0.78f, 0.23f, false)
    val equipmentLayout = Layout(floatArrayOf(0.328f, 0.494f, 0.658f), 0.32f, 0.37f..0.47f, 0.09f, 0.62f, 0.09f, 0.765f, 0.293f, true)

    private val gearAlt = mapOf(
        "ARMADURA" to "LEXCORP", "RIFLE" to "PERSONALIZADO", "KAMA" to "REINO", "BASTOES" to "ESCRIMA",
        "MANHA" to "ENESIMO", "FACAS" to "ADEPTO", "DIVINO" to "CAPACETE",
    )
    // O OCR costuma perder "Bastões" sobre a arte azul; estas palavras aparecem na segunda linha do nome.
    private val gearListAlt = mapOf("BASTOES" to "ESCRIMA|ENERGIZADOS")
    private fun gear(label: String, key: String) = Target(label, key, false, alt = gearAlt[key], listAlt = gearListAlt[key])

    val equip2 = listOf(
        listOf(gear("Placa Peitoral Divina do Quarto Mundo", "PLACA"), gear("Maça Divina do Quarto Mundo", "MACA"),
            gear("Facas de Adepto da Liga dos Assassinos", "FACAS")),
        listOf(gear("Rifle Personalizado", "RIFLE"), gear("Kama do Reino Inferior", "KAMA"),
            gear("Estrela da Manhã de Metal Enésimo", "MANHA")),
        listOf(gear("Bastões de Escrima Energizados", "BASTOES"), gear("Armadura Peitoral LexCorp V1", "ARMADURA"),
            gear("Traje do Caçador de Marte", "CACADOR")),
    )

    val equip1 = listOf(
        listOf(gear("Rifle Personalizado", "RIFLE"), gear("Armadura Peitoral LexCorp V1", "ARMADURA"),
            gear("Traje de Hipervelocidade Revestido", "REVESTIDO")),
        listOf(gear("Bastões de Escrima Energizados", "BASTOES"), gear("Estrela da Manhã de Metal Enésimo", "MANHA"),
            gear("Kama do Reino Inferior", "KAMA")),
        listOf(gear("Placa Peitoral Divina do Quarto Mundo", "PLACA"), gear("Maça Divina do Quarto Mundo", "MACA"),
            gear("Capacete Divino do Quarto Mundo", "DIVINO")),
    )

    val lobbyCardX = floatArrayOf(0.1435f, 0.305f, 0.466f)
}
