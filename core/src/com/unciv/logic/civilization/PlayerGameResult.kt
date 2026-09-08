package com.unciv.logic.civilization

/** Public end-of-game text, or an individual defeat before a winner is established. */
data class PlayerGameResult(
    val outcome: String,
    val winningCivilizationId: String?,
    val winningCivilizationName: String?,
    val victoryType: String?,
    val victoryTurn: Int?,
    val title: String,
    val paragraphs: List<String>,
)
