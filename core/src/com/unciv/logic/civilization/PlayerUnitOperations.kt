package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitSettlement
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/** Ordinary single-turn movement and settlement. Special relocation modes use their own operations. */
class PlayerUnitOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class MovePreview(val destination: Tile, val path: List<Tile>, val movementCost: Float)

    @Readonly
    fun owns(unit: MapUnit): Boolean = unit.civ === civ && !unit.isDestroyed &&
        civ.units.getCivUnits().any { it === unit } && unit.currentTile.getUnits().any { it === unit }

    @Readonly
    fun supportsMovement(unit: MapUnit): Boolean = owns(unit) && !unit.baseUnit.isAirUnit() &&
        !unit.isPreparingParadrop() && !unit.isPreparingAirSweep()

    /** Speculative paths use only explored terrain and visible units. No path is a promise of arrival. */
    @Readonly @Suppress("purity") // getPathToTile constructs a detached path; no game state changes
    fun moves(unit: MapUnit): List<MovePreview> {
        if (!supportsMovement(unit) || !unit.hasMovement() || unit.cache.cannotMove) return emptyList()
        val paths = unit.movement.getMovementToTilesAtPosition(unit.currentTile.position, unit.currentMovement, forPlanning = true)
        return paths.entries.filter { (tile, _) ->
            tile !== unit.currentTile && unit.movement.canMoveTo(tile, forPlanning = true)
        }.map { (tile, entry) -> MovePreview(tile, paths.getPathToTile(tile), entry.totalMovement) }
    }

    fun tryMove(unit: MapUnit, destination: Tile): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val preview = moves(unit).firstOrNull { it.destination === destination } ?: return@synchronized false
        unit.action = null
        unit.movement.moveToTile(destination, plannedPath = preview.path)
        // An obstacle discovered while moving can stop a valid order short of its destination.
        true
    }

    @Readonly
    fun canFoundCity(unit: MapUnit): Boolean = PlayerOperations(civ, spectatorMode).canAct() && owns(unit) && UnitSettlement.canFoundCity(unit)

    fun tryFoundCity(unit: MapUnit, confirmBreakPromise: Boolean = false): Boolean = synchronized(civ.gameInfo) {
        canFoundCity(unit) && UnitSettlement.tryFoundCity(unit, confirmBreakPromise) != null
    }
}
