package com.unciv.logic.map.mapunit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import yairm210.purity.annotations.Readonly

/** Settlement rules and effects, shared by interactive and automated callers. */
object UnitSettlement {
    @Readonly
    fun foundingUnique(unit: MapUnit, tile: Tile = unit.currentTile): Unique? {
        if (tile.isWater || tile.isImpassible()) return null
        if (unit.civ.isOneCityChallenger() && unit.civ.hasEverOwnedOriginalCapital) return null
        return UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.FoundCity).firstOrNull()
            ?: UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.FoundPuppetCity).firstOrNull()
    }

    @Readonly
    fun canFoundCity(unit: MapUnit): Boolean {
        val unique = foundingUnique(unit) ?: return false
        return !unit.isDestroyed && unit.hasMovement() && unit.currentTile.canBeSettled(unit.civ) &&
            UnitActionModifiers.canActivateSideEffects(unit, unique)
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
