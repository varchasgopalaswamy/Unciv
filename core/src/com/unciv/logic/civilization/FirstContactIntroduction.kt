package com.unciv.logic.civilization

/** Text displayed by the first-contact popup, detached from mutable game objects.
 *
 * [leaderName] is the displayed civilization caption, including any leader title and
 * multiplayer player type. [message] and [acknowledgement] are translation source
 * strings, just like the text supplied to the other diplomacy popups.
 */
data class FirstContactIntroduction(
    val civilizationId: String,
    val civilizationName: String,
    val leaderName: String,
    val message: String,
    val acknowledgement: String,
)
