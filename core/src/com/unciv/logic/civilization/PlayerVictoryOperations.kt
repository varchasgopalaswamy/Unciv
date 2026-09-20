package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import java.util.Collections

/** The human spaceship and world-leader choices used by the ordinary desktop controls.
 * Queries are detached; every mutation rechecks authority and current native eligibility.
 */
class PlayerVictoryOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class SpaceshipPart(val title: String, val available: Boolean, val reasons: List<String>)
    data class Candidate(val civilizationId: String, val name: String)

    fun spaceshipPart(unit: MapUnit): SpaceshipPart? {
        if (spectatorMode || civ.isSpectator() || !PlayerUnitOperations(civ).owns(unit)) return null
        val unique = unit.getMatchingUniques(UniqueType.AddInCapital).firstOrNull() ?: return null
        val tile = unit.currentTile
        val reasons = arrayListOf<String>()
        if (!PlayerOperations(civ, spectatorMode).canAct()) reasons.add("This player cannot act now")
        if (!tile.isCityCenter() || tile.getCity()?.let { it.isCapital() && it.civ === civ } != true)
            reasons.add("Move to your capital's city center first")
        // The native AddInCapital action does not require movement, consume resources or use consume().
        return SpaceshipPart("Add to [${unique.params[0]}]", reasons.isEmpty(), Collections.unmodifiableList(reasons))
    }

    fun tryAddSpaceshipPart(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (spaceshipPart(unit)?.available != true) return@synchronized false
        civ.victoryManager.currentsSpaceshipParts.add(unit.name, 1)
        unit.destroy()
        true
    }

    fun voteCandidates(): List<Candidate> = Collections.unmodifiableList(
        civ.diplomacyFunctions.getKnownCivsSorted(false).map { Candidate(it.civID, it.civName) }.toList()
    )

    fun canVote() = PlayerOperations(civ, spectatorMode).canAct() && civ.mayVoteForDiplomaticVictory()

    /** Null explicitly abstains; a recorded null still counts as having voted. */
    fun tryVote(candidateId: String?): Boolean = synchronized(civ.gameInfo) {
        if (!canVote() || (candidateId != null && voteCandidates().none { it.civilizationId == candidateId }))
            return@synchronized false
        civ.diplomaticVoteForCiv(candidateId)
        true
    }

    fun canAcknowledgeResults() = PlayerOperations(civ, spectatorMode).canAct() && civ.shouldShowDiplomaticVotingResults()

    fun tryAcknowledgeResults(): Boolean = synchronized(civ.gameInfo) {
        if (!canAcknowledgeResults()) return@synchronized false
        civ.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1)
        true
    }
}
