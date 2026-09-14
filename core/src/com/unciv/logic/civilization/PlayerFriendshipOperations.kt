package com.unciv.logic.civilization

import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.unique.UniqueType

/** The text and choices of an existing, player-owned friendship request. */
data class FriendshipDecision(
    val civilizationId: String,
    val content: InformationalPopupContent,
    val unavailableReason: String?,
)

/** Friendship eligibility can be inspected out of turn; popup responses require the active player. */
class PlayerFriendshipOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    companion object {
        const val DURATION = 30
    }

    fun stateReason(other: Civilization): String? = synchronized(civ.gameInfo) {
        when {
            spectatorMode || !civ.isMajorCiv() || !other.isMajorCiv() || civ === other ||
                civ.gameInfo.civilizations.none { it === civ } ||
                civ.gameInfo.civilizations.none { it === other } ||
                !civ.knows(other) || !other.knows(civ) -> "Friendship requires contact between major civilizations."
            civ.isDefeated() || other.isDefeated() -> "A civilization has been defeated."
            civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange) -> "Diplomatic relationships cannot change."
            civ.isAtWarWith(other) -> "Friendship requires peace."
            civ.getDiplomacyManager(other)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> "A declaration of friendship is already active."
            !civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other) ||
                !other.diplomacyFunctions.canSignDeclarationOfFriendshipWith(civ) -> "A denunciation prevents friendship."
            else -> null
        }
    }

    fun decision(alert: PopupAlert): FriendshipDecision? = synchronized(civ.gameInfo) {
        if (spectatorMode || !civ.isMajorCiv() || civ.gameInfo.civilizations.none { it === civ } ||
            alert.type != AlertType.DeclarationOfFriendship ||
            civ.popupAlerts.none { it === alert }) return@synchronized null
        val other = civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value && it.isMajorCiv() }
            ?: return@synchronized null
        if (!civ.knows(other) || !other.knows(civ)) return@synchronized null
        FriendshipDecision(other.civID, InformationalPopupContent(
            title = other.getLeaderDisplayName(),
            paragraphs = listOf("DECLARATION OF FRIENDSHIP", other.nation.declaringFriendship.ifEmpty {
                "My friend, shall we declare our friendship to the world?"
            }),
            acknowledgement = "Declare Friendship ([$DURATION] turns)",
            additionalAcknowledgements = listOf("We are not interested."),
        ), stateReason(other))
    }

    fun tryRespond(alert: PopupAlert, accept: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val decision = decision(alert) ?: return@synchronized false
        if (accept && decision.unavailableReason != null) return@synchronized false
        val other = civ.gameInfo.getCivilization(decision.civilizationId)
        if (accept) civ.getDiplomacyManager(other)!!.signDeclarationOfFriendship()
        else other.getDiplomacyManager(civ)!!.setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
        civ.popupAlerts.remove(alert)
        true
    }
}
