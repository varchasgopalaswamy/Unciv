package com.unciv.logic.map.mapunit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly

enum class FoundingUnitRejection {
    DESTROYED, ONE_CITY_CHALLENGE, FOUNDING_ABILITY_UNAVAILABLE, NO_MOVEMENT, ACTION_REQUIREMENTS_NOT_MET
}

/** Settlement rules and effects, shared by interactive and automated callers. */
object UnitSettlement {
    @Readonly
    fun hasFoundingAbility(unit: MapUnit): Boolean =
        unit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals) ||
            unit.hasUnique(UniqueType.FoundPuppetCity, GameContext.IgnoreConditionals)

    @Readonly
    private fun usableFoundingUnique(unit: MapUnit): Unique? =
        UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.FoundCity).firstOrNull()
            ?: UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.FoundPuppetCity).firstOrNull()

    @Readonly
    fun foundingUnique(unit: MapUnit, tile: Tile = unit.currentTile): Unique? {
        if (tile.isWater || tile.isImpassible()) return null
        if (unit.civ.isOneCityChallenger() && unit.civ.hasEverOwnedOriginalCapital) return null
        return usableFoundingUnique(unit)
    }

    @Readonly
    fun canFoundCity(unit: MapUnit): Boolean = canFoundCity(unit, unit.currentTile)

    /** Checks a prospective city site before movement; founding still requires occupying that tile. */
    @Readonly
    fun canFoundCity(unit: MapUnit, tile: Tile): Boolean =
        unitBlockers(unit).isEmpty() && tile.canBeSettled(unit.civ)

    /** Unit requirements are separate from site restrictions: waiting only restores movement. */
    @Readonly
    fun unitBlockers(unit: MapUnit): List<FoundingUnitRejection> {
        if (unit.isDestroyed) return listOf(FoundingUnitRejection.DESTROYED)
        @LocalState val blockers = ArrayList<FoundingUnitRejection>()
        if (unit.civ.isOneCityChallenger() && unit.civ.hasEverOwnedOriginalCapital)
            blockers.add(FoundingUnitRejection.ONE_CITY_CHALLENGE)
        val unique = usableFoundingUnique(unit)
        if (unique == null) blockers.add(FoundingUnitRejection.FOUNDING_ABILITY_UNAVAILABLE)
        if (!unit.hasMovement()) blockers.add(FoundingUnitRejection.NO_MOVEMENT)
        else if (unique != null && !UnitActionModifiers.canActivateSideEffects(unit, unique))
            blockers.add(FoundingUnitRejection.ACTION_REQUIREMENTS_NOT_MET)
        return blockers
    }

    /** Names shown by the settlement confirmation, in stable order. */
    @Readonly
    fun promisesBroken(unit: MapUnit): List<String> = unit.civ.getKnownCivs()
        .filter { it.isMajorCiv() && !unit.civ.isAtWarWith(it) }
        .filter { other ->
            other.getDiplomacyManager(unit.civ)?.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs) == true &&
                other.cities.any { city ->
                    city.getCenterTile().aerialDistanceTo(unit.currentTile) <= 6 && other.hasExplored(city.getCenterTile())
                }
        }.map { it.getLeaderDisplayName() }.sorted().toList()

    /** Revalidates after a picker/confirmation has been open. Presentation effects belong to the caller. */
    fun tryFoundCity(unit: MapUnit, confirmBreakPromise: Boolean = false): City? = synchronized(unit.civ.gameInfo) {
        if (!canFoundCity(unit) || !confirmBreakPromise && promisesBroken(unit).isNotEmpty()) return@synchronized null
        if (unit.civ.units.getCivUnits().none { it === unit } || unit.currentTile.getUnits().none { it === unit }) return@synchronized null
        val unique = foundingUnique(unit) ?: return@synchronized null
        val city = unit.civ.addCity(unit.currentTile.position, unit)
        if (unique.modifiers.any { it.type?.targetTypes?.contains(UniqueTarget.UnitActionModifier) == true })
            UnitActionModifiers.activateSideEffects(unit, unique)
        else unit.destroy()
        if (unique.type == UniqueType.FoundPuppetCity) city.isPuppet = true
        city
    }
}
