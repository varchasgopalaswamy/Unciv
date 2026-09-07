package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.ReligionState
import yairm210.purity.annotations.Readonly

/** Gameplay choices offered before passing a human player's turn.
 *
 * Presentation preferences (cycling idle units, confirmation dialogs, and when to
 * run automated units) are deliberately separate. Callers decide how to present
 * these requirements; dismissing a picker does not resolve its underlying choice.
 * This function neither advances a turn nor changes any picker flags.
 */
object PlayerTurnRequirements {
    enum class Kind {
        FreeGreatPerson, Construction, Research, Policy, MoveSpies,
        FoundPantheon, ExpandPantheon, FoundReligion, EnhanceReligion,
        ReformReligion, DiplomaticVote,
    }

    @Readonly
    fun cityNeedingConstruction(civ: Civilization): City? =
        civ.cities.firstOrNull { !it.isPuppet && it.cityConstructions.currentConstructionName().isEmpty() }

    /** Ordered like the ordinary next-turn button. The result contains no mutable engine objects. */
    @Readonly
    fun pending(civ: Civilization): List<Kind> =
        Kind.entries.filter { isPending(civ, it) }

    @Readonly
    fun isPending(civ: Civilization, kind: Kind): Boolean {
        if (civ.isSpectator()) return false
        val religion = civ.religionManager
        return when (kind) {
            Kind.FreeGreatPerson -> civ.greatPeople.freeGreatPeople > 0
            Kind.Construction -> cityNeedingConstruction(civ) != null
            Kind.Research -> civ.shouldOpenTechPicker()
            Kind.Policy -> civ.policies.shouldShowPolicyPicker()
            Kind.MoveSpies -> civ.gameInfo.isEspionageEnabled() && civ.espionageManager.shouldShowMoveSpies()
            Kind.FoundPantheon -> religion.religionState != ReligionState.Pantheon && religion.canFoundOrExpandPantheon()
            Kind.ExpandPantheon -> religion.religionState == ReligionState.Pantheon && religion.canFoundOrExpandPantheon()
            Kind.FoundReligion -> religion.religionState == ReligionState.FoundingReligion
            Kind.EnhanceReligion -> religion.religionState == ReligionState.EnhancingReligion
            Kind.ReformReligion -> religion.hasFreeBeliefs()
            Kind.DiplomaticVote -> civ.mayVoteForDiplomaticVictory()
        }
    }
}
