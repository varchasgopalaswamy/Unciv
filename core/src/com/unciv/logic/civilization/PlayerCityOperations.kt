package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/** Validated city-screen actions, sharing the game monitor with other player operations.
 *
 * Citizen assignment intentionally does not steal a worker from another tile: the city
 * screen first requires an unassigned citizen. Unassigning a tile also clears its lock.
 * UI callers must additionally respect their local input-disabled state.
 */
class PlayerCityOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    @Readonly
    private fun ownsActiveCity(city: City): Boolean = PlayerOperations(civ, spectatorMode).canAct() &&
        city.civ === civ && civ.cities.any { it === city }

    @Readonly
    fun canManage(city: City): Boolean = ownsActiveCity(city) && !city.isPuppet

    /** Matches the city map's WORKABLE tile state, before checking available population. */
    @Readonly
    fun isAssignableTile(city: City, tile: Tile): Boolean = city.civ === civ &&
        civ.cities.any { it === city } && tile in city.tilesInRange &&
        tile.getOwner() === civ && !tile.isCityCenter() &&
        (!tile.isWorked() || tile.getWorkingCity() === city) &&
        !tile.stats.getTileStats(city, civ).isEmpty() && !tile.isBlockaded() &&
        (city.isWorked(tile) || !tile.providesYield())

    @Readonly
    fun canSetWorkedTile(city: City, tile: Tile, worked: Boolean): Boolean =
        canManage(city) && isAssignableTile(city, tile) &&
            if (worked) !tile.providesYield() && city.population.getFreePopulation() > 0
            else city.isWorked(tile)

    fun trySetWorkedTile(city: City, tile: Tile, worked: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!canSetWorkedTile(city, tile, worked)) return@synchronized false
        val changed = if (worked) city.workTile(tile) else city.stopWorkingTile(tile)
        if (changed) city.cityStats.update()
        changed
    }

    @Readonly
    fun canSetTileLocked(city: City, tile: Tile, locked: Boolean): Boolean =
        ownsActiveCity(city) && tile in city.tilesInRange && city.isWorked(tile) && tile.isLocked() != locked

    fun trySetTileLocked(city: City, tile: Tile, locked: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!canSetTileLocked(city, tile, locked)) return@synchronized false
        if (locked) city.lockTile(tile) else city.unlockTile(tile)
    }

    @Readonly
    fun canRemoveConstruction(city: City, index: Int): Boolean =
        canManage(city) && index in city.cityConstructions.constructionQueue.indices

    /** Manual removal preserves accumulated production and leaves Idle when the queue empties. */
    fun tryRemoveConstruction(city: City, index: Int, automatic: Boolean = false): Boolean = synchronized(civ.gameInfo) {
        if (!canRemoveConstruction(city, index)) return@synchronized false
        city.cityConstructions.removeFromQueue(index, automatic)
        city.cityStats.update()
        true
    }

    @Readonly
    fun canMoveConstruction(city: City, index: Int, toIndex: Int): Boolean =
        canManage(city) && index in city.cityConstructions.constructionQueue.indices &&
        toIndex in city.cityConstructions.constructionQueue.indices && index != toIndex

    /** Equivalent to repeated city-screen priority arrows, including duplicate and perpetual entries. */
    fun tryMoveConstruction(city: City, index: Int, toIndex: Int): Boolean = synchronized(civ.gameInfo) {
        if (!canMoveConstruction(city, index, toIndex)) return@synchronized false
        var current = index
        while (current > toIndex) current = city.cityConstructions.raisePriority(current)
        while (current < toIndex) current = city.cityConstructions.lowerPriority(current)
        city.cityStats.update()
        true
    }
}
