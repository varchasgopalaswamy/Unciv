package com.unciv.logic.map.mapunit

import com.unciv.Constants
import com.unciv.logic.MultiFilter
import com.unciv.logic.civilization.managers.ImprovementFunctions
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** Repair rules shared by unit actions and automation. Player ownership is checked by the caller. */
object UnitWorkerRepair {
    @Readonly
    fun isShown(unit: MapUnit): Boolean {
        val tile = unit.currentTile
        if (Constants.repair !in tile.ruleset.tileImprovements || !unit.cache.hasUniqueToBuildImprovements ||
            unit.isEmbarked() || tile.isCityCenter() || !tile.isPillaged()) return false
        val improvement = tile.getImprovementToRepair() ?: return false
        return unit.getMatchingUniques(UniqueType.BuildImprovements).any { unique ->
            MultiFilter.multiFilter(unique.params[0], {
                improvement.matchesFilter(it, tile.stateThisTile) || tile.matchesTerrainFilter(it, unit.civ)
            })
        }
    }

    @Readonly
    fun turns(unit: MapUnit): Int {
        val tile = unit.currentTile
        if (!tile.isPillaged()) return 0
        if (tile.improvementInProgress == Constants.repair) return tile.turnsToImprovement
        val repairTurns = tile.ruleset.tileImprovements[Constants.repair]!!.getTurnsToBuild(unit.civ, unit)
        return repairTurns.coerceAtMost(tile.getImprovementToRepair()!!.getTurnsToBuild(unit.civ, unit))
    }

    @Readonly
    fun canRepair(unit: MapUnit): Boolean {
        if (!isShown(unit)) return false
        val tile = unit.currentTile
        return unit.hasMovement() && tile.improvementInProgress != Constants.repair && !tile.isEnemyTerritory(unit.civ) &&
            ImprovementFunctions.getImprovementBuildingProblems(tile.getImprovementToRepair()!!,
                GameContext(civInfo = unit.civ, unit = unit, tile = tile))
                .none { it == ImprovementBuildingProblem.OutsideBorders }
    }

    fun tryRepair(unit: MapUnit): Boolean {
        if (!canRepair(unit)) return false
        // Preserve the unit action's ordering: an existing construction remains ahead of repair.
        unit.currentTile.queueImprovement(Constants.repair, turns(unit))
        unit.action = null
        return true
    }
}
