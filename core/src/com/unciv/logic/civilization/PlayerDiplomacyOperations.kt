package com.unciv.logic.civilization

import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.unique.UniqueType
import java.util.Collections

/** A detached action offered by the diplomacy screen, including why it is disabled. */
data class PlayerDiplomaticAction(
    val available: Boolean,
    val unavailableReasons: List<String>,
)

/** Public relationship and war-declaration choices for a known major civilization. */
data class PlayerDiplomacyOptions(
    val status: DiplomaticStatus,
    val peaceTreatyTurns: Int,
    val declareWar: PlayerDiplomaticAction,
    val declarationConfirmation: List<String>,
)

/** Validated major-civilization diplomacy actions, serialized on the game monitor.
 *
 * Reading the screen is allowed out of turn. Mutations additionally require the
 * active human player, and UI callers must still respect local input-disabled state.
 */
class PlayerDiplomacyOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    private fun canObserve(other: Civilization): Boolean = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ } && other !== civ &&
        civ.gameInfo.civilizations.any { it === other } && other.gameInfo === civ.gameInfo &&
        !other.isSpectator() && !other.isBarbarian && civ.knows(other) && other.knows(civ)

    fun options(other: Civilization): PlayerDiplomacyOptions? = synchronized(civ.gameInfo) {
        if (!canObserve(other) || !other.isMajorCiv()) return@synchronized null
        val manager = civ.getDiplomacyManager(other) ?: return@synchronized null
        val treatyTurns = manager.turnsToPeaceTreaty()
        val reasons = ArrayList<String>()
        if (!PlayerOperations(civ, spectatorMode).canAct()) reasons.add("Not the active human player.")
        if (civ.isDefeated()) reasons.add("Your civilization has been defeated.")
        if (other.isDefeated()) reasons.add("Target civilization has been defeated.")
        if (civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange))
            reasons.add("Diplomatic relationships cannot change.")
        if (manager.diplomaticStatus == DiplomaticStatus.War) reasons.add("Already at war.")
        if (treatyTurns > 0) reasons.add("Peace treaty active for [$treatyTurns] turns.")
        if (reasons.isEmpty() && !manager.canDeclareWar()) reasons.add("War cannot be declared.")
        PlayerDiplomacyOptions(
            status = manager.diplomaticStatus,
            peaceTreatyTurns = treatyTurns,
            declareWar = PlayerDiplomaticAction(reasons.isEmpty(), immutable(reasons)),
            declarationConfirmation = declarationConfirmation(other) ?: emptyList(),
        )
    }

    /** The diplomacy confirmation's warning text, with unknown pact partners unnamed.
     *
     * City-state callers may reuse the confirmation while retaining their own action
     * rules. Defensive-pact chains are excluded, as in the war declaration itself.
     */
    fun declarationConfirmation(other: Civilization): List<String>? = synchronized(civ.gameInfo) {
        if (!canObserve(other)) return@synchronized null
        val lines = arrayListOf("Declare war on [${other.civName}]?")
        if (other.getDiplomacyManager(civ)!!.hasFlag(DiplomacyFlags.AgreedToNotAttackUs))
            lines.add("This will break your promise to not attack them. Other leaders will view this unfavorably.")
        val defendingPartners = other.diplomacy.values.filter {
            it.otherCiv !== civ && it.diplomaticStatus == DiplomaticStatus.DefensivePact &&
                !it.otherCiv.isAtWarWith(civ)
        }.map { it.otherCiv }
        for (partner in defendingPartners) {
            val name = if (civ.knows(partner)) partner.civName else "An unknown civilization"
            lines.add("[$name] will also join them in the war")
        }
        for (manager in civ.diplomacy.values) {
            if (manager.otherCiv !== other && manager.diplomaticStatus == DiplomaticStatus.DefensivePact &&
                manager.otherCiv !in defendingPartners)
                lines.add("This will cancel your defensive pact with [${manager.otherCiv.civName}]")
        }
        immutable(lines)
    }

    fun tryDeclareWar(other: Civilization): Boolean = synchronized(civ.gameInfo) {
        if (options(other)?.declareWar?.available != true) return@synchronized false
        civ.getDiplomacyManager(other)!!.declareWar()
        true
    }

    /** The defending leader's response shown after confirming a declaration of war. */
    fun declarationResponse(other: Civilization): InformationalPopupContent? = synchronized(civ.gameInfo) {
        if (!canObserve(other)) return@synchronized null
        InformationalPopupContent(
            title = other.getLeaderDisplayName(),
            paragraphs = immutable(listOf(other.nation.attacked).filter(String::isNotEmpty)),
            acknowledgement = "Very well.",
        )
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}
