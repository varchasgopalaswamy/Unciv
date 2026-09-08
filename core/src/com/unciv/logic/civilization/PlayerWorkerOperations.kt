package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitWorkerRepair
import com.unciv.logic.map.mapunit.WorkerImprovementPicker
import com.unciv.models.ruleset.unique.UniqueType

/** Ordinary worker orders. Every accepted order rechecks the live picker and player authority. */
class PlayerWorkerOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Job(val name: String, val remainingTurns: Int)

    fun ownsWorker(unit: MapUnit): Boolean = PlayerUnitOperations(civ).owns(unit) &&
        unit.cache.hasUniqueToBuildImprovements && unit.getMatchingUniques(UniqueType.BuildImprovements).any()

    fun queue(unit: MapUnit): List<Job> = if (!ownsWorker(unit)) emptyList()
        else unit.currentTile.improvementQueue.map { Job(it.improvement, it.turnsToImprovement) }

    fun canUsePicker(unit: MapUnit): Boolean = PlayerOperations(civ, spectatorMode).canAct() && ownsWorker(unit) &&
        WorkerImprovementPicker.canOpen(unit) && !unit.currentTile.isMarkedForCreatesOneImprovement()

    fun canStartJob(unit: MapUnit, name: String): Boolean {
        if (!PlayerOperations(civ, spectatorMode).canAct() || !ownsWorker(unit)) return false
        if (name == Constants.repair) return UnitWorkerRepair.canRepair(unit)
        if (name == Constants.cancelImprovementOrder || !canUsePicker(unit)) return false
        val option = WorkerImprovementPicker(unit).options().firstOrNull { it.improvement.name == name } ?: return false
        return option.report.isEmpty() || option.report.isQueueable()
    }

    /** Select the final improvement; a queueable feature removal is prepended exactly as in the picker. */
    fun tryStartJob(unit: MapUnit, name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canStartJob(unit, name)) return@synchronized false
        if (name == Constants.repair) return@synchronized UnitWorkerRepair.tryRepair(unit)
        val option = WorkerImprovementPicker(unit).options().single { it.improvement.name == name }
        val first = option.report.removalImprovement ?: option.improvement
        val tile = unit.currentTile
        // Selecting the current job wakes the worker without restarting its progress or queue.
        if (first.name != tile.improvementInProgress) {
            tile.startWorkingOnImprovement(first, civ, unit)
            if (first !== option.improvement) tile.queueImprovement(option.improvement, civ, unit)
        }
        unit.action = null
        true
    }

    fun canStopJob(unit: MapUnit): Boolean = canUsePicker(unit) &&
        WorkerImprovementPicker(unit).options().any { it.improvement.name == Constants.cancelImprovementOrder && it.report.isEmpty() }

    fun tryStopJob(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (!canStopJob(unit)) return@synchronized false
        unit.currentTile.stopWorkingOnImprovement()
        true
    }
}
