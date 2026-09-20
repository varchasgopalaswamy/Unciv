package com.unciv.logic.civilization

import com.unciv.logic.battle.AirInterception
import com.unciv.logic.battle.AttackResolution
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** Explicit relocation and special missions, independent of desktop selection modes.
 * Planning never selects an interceptor or enumerates unseen nuclear victims.
 * The native full-state legality check still applies immediately before execution.
 */
class PlayerAirOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    enum class Mission { REBASE, PARADROP, AIR_SWEEP, NUCLEAR_STRIKE }
    data class Result(val mission: Mission, val resolution: AttackResolution?)

    data class Option(val mission: Mission, val range: Int, val available: Boolean, val reasons: List<String>)
    data class Preview(
        val mission: Mission,
        val destination: Tile,
        val range: Int,
        val available: Boolean,
        val reasons: List<String>,
        val blastRadius: Int,
        val affectedTiles: List<Tile>,
        val visibleVictims: List<ICombatant>,
        val knownDeclarations: List<Civilization>,
        val speculative: Boolean,
    )

    @Readonly
    private fun canInspect(unit: MapUnit) = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ } && PlayerUnitOperations(civ).owns(unit)

    @Readonly @Suppress("purity")
    fun options(unit: MapUnit): List<Option> {
        if (!canInspect(unit)) return emptyList()
        return Mission.entries.mapNotNull { mission ->
            val range = when (mission) {
                Mission.REBASE -> unit.getMaxMovementForAirUnits().takeIf { unit.baseUnit.isAirUnit() }
                Mission.PARADROP -> unit.getParadropDestinationTileFilters().values.maxOrNull()
                Mission.AIR_SWEEP -> unit.getRange().takeIf { unit.hasUnique(UniqueType.CanAirsweep) }
                Mission.NUCLEAR_STRIKE -> unit.getRange().takeIf { unit.isNuclearWeapon() }
            } ?: return@mapNotNull null
            val reasons = buildList {
                if (!PlayerOperations(civ, spectatorMode).canAct()) add("This player cannot act now")
                if (!unit.hasMovement()) add("No movement remaining")
                when (mission) {
                    Mission.REBASE, Mission.PARADROP -> if (unit.cache.cannotMove) add("This unit cannot move")
                    Mission.AIR_SWEEP, Mission.NUCLEAR_STRIKE -> if (!unit.canAttack()) add("This unit cannot attack now")
                }
                if (mission == Mission.PARADROP && unit.hasUnitMovedThisTurn()) add("The unit has already moved this turn")
            }
            Option(mission, range, reasons.isEmpty(), reasons)
        }
    }

    @Readonly @Suppress("purity")
    fun preview(unit: MapUnit, mission: Mission, destination: Tile): Preview? {
        if (destination.tileMap !== civ.gameInfo.tileMap) return null
        val option = options(unit).firstOrNull { it.mission == mission } ?: return null
        val reasons = option.reasons.toMutableList()
        if (unit.currentTile.aerialDistanceTo(destination) > option.range) addDestinationReason(reasons)
        when (mission) {
            Mission.REBASE -> {
                if (destination === unit.currentTile || !destination.isExplored(civ) ||
                    !unit.movement.canMoveTo(destination)) addDestinationReason(reasons)
            }
            Mission.PARADROP -> {
                if (destination === unit.currentTile || !unit.movement.canParadropOn(destination) ||
                    !unit.movement.canMoveTo(destination, forPlanning = true)) addDestinationReason(reasons)
            }
            Mission.AIR_SWEEP -> Unit // Interceptors are unknown until the mission resolves.
            Mission.NUCLEAR_STRIKE -> {
                if (!Nuke.mayUseNuke(MapUnitCombatant(unit), destination, forPlanning = true)) addDestinationReason(reasons)
            }
        }
        val radius = if (mission == Mission.NUCLEAR_STRIKE) unit.getNukeBlastRadius() else 0
        val affected = if (mission == Mission.NUCLEAR_STRIKE)
            destination.getTilesInDistance(radius).filter { it.isExplored(civ) }.toList() else emptyList()
        val victims = affected.flatMap { tile ->
            listOfNotNull(tile.getCity()?.takeIf { tile.isCityCenter() && tile.isVisible(civ) }?.let(::CityCombatant)) +
                tile.getUnits().filter { it.civ === civ || it.isVisibleTo(civ) }.map(::MapUnitCombatant).toList()
        }
        val declarations = (affected.mapNotNull { it.getOwner() } + victims.map { it.getCivInfo() })
            .filter { it !== civ && civ.knows(it) && !it.isBarbarian && !civ.isAtWarWith(it) }.distinct()
        return Preview(mission, destination, option.range, reasons.isEmpty(), reasons.distinct(), radius,
            affected, victims, declarations,
            mission == Mission.NUCLEAR_STRIKE || mission == Mission.AIR_SWEEP || !destination.isVisible(civ))
    }

    @Readonly @Suppress("purity")
    private fun addDestinationReason(reasons: MutableList<String>) {
        reasons.add("This destination is unavailable for this mission")
    }

    fun tryExecute(unit: MapUnit, mission: Mission, destination: Tile): Boolean =
        tryExecuteWithResult(unit, mission, destination) != null

    fun tryExecuteWithResult(unit: MapUnit, mission: Mission, destination: Tile): Result? = synchronized(civ.gameInfo) {
        if (preview(unit, mission, destination)?.available != true) return@synchronized null
        var resolution: AttackResolution? = null
        when (mission) {
            Mission.REBASE -> unit.movement.moveToTile(destination)
            Mission.PARADROP -> {
                if (!unit.movement.canMoveTo(destination)) return@synchronized null
                unit.action = UnitActionType.Paradrop.value
                unit.movement.moveToTile(destination)
            }
            Mission.AIR_SWEEP -> {
                unit.action = UnitActionType.AirSweep.value
                AirInterception.airSweep(MapUnitCombatant(unit), destination)
                resolution = AttackResolution.Completed
            }
            Mission.NUCLEAR_STRIKE -> {
                val attacker = MapUnitCombatant(unit)
                if (!Nuke.mayUseNuke(attacker, destination)) return@synchronized null
                unit.action = null
                resolution = Nuke.nukeWithResult(attacker, destination)
            }
        }
        Result(mission, resolution)
    }
}
