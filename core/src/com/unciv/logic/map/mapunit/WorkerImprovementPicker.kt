package com.unciv.logic.map.mapunit

import com.unciv.Constants
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent

/** The ordinary improvement picker's choices and explanatory text, independent of screen state.
 * Construct a fresh instance after changing the unit, tile, or civilization.
 */
class WorkerImprovementPicker(private val unit: MapUnit) {
    private val tile = unit.currentTile
    private val currentPlayerCiv = unit.civ
    private val ruleset = tile.ruleset
    private val tileWithoutLastTerrain = getTileWithoutLastTerrain()
    private val maxErasForward = ruleset.modOptions.constants.maxImprovementTechErasForward.takeUnless { it < 0 } ?: Int.MAX_VALUE

    data class Option(val improvement: TileImprovement, val report: ProblemReport)

    fun options(): List<Option> = ruleset.tileImprovements.values.mapNotNull { improvement ->
        if (improvement.turnsToBuild == -1 && improvement.name != Constants.cancelImprovementOrder) return@mapNotNull null
        if (improvement.name == tile.improvement || !unit.canBuildImprovement(improvement)) return@mapNotNull null
        val report = getProblemReport(improvement) ?: return@mapNotNull null
        Option(improvement, report)
    }

    companion object {
        fun canReport(problems: Collection<ImprovementBuildingProblem>) = problems.all { it.reportable }

        /** Whether the unit action can open the picker; a picker may contain only unavailable choices. */
        fun canOpen(unit: MapUnit): Boolean = unit.cache.hasUniqueToBuildImprovements &&
            unit.getMatchingUniques(UniqueType.BuildImprovements).any() && unit.hasMovement() &&
            !unit.currentTile.isCityCenter() && unit.currentTile.ruleset.tileImprovements.values.any {
                canReport(unit.currentTile.improvementFunctions.getImprovementBuildingProblems(it, unit.cache.state).toSet()) &&
                    unit.canBuildImprovement(it)
            }
    }

    private fun getTileWithoutLastTerrain(): Tile? {
        // clone tileInfo without "top" feature if it could be removed
        // Keep this copy around for speed (in tileWithoutLastTerrain)
        if (Constants.remove + tile.lastTerrain.name !in ruleset.tileImprovements) return null
        val newTile = tile.clone(addUnits = false)
        newTile.setTerrainTransients()
        newTile.removeTerrainFeature(newTile.lastTerrain.name)
        return newTile
    }

    /** Calculate maintenance costs, matching logic in [getTransportationUpkeep][com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.getTransportationUpkeep] */
    // Not centralized in [TileStatFunctions] because the actual upkeep calculation can optimize some things and rounding errors might accumulate differently
    fun getMaintenance(improvement: TileImprovement): Stats {
        val maintenance = Stats()
        if (currentPlayerCiv.getMatchingUniques(UniqueType.NoImprovementMaintenanceInSpecificTiles)
                .any { tile.matchesFilter(it.params[0], currentPlayerCiv) }
        ) return maintenance

        val context = GameContext(currentPlayerCiv, tile = tile)
        val maintenanceUniques = improvement.getMatchingUniques(UniqueType.ImprovementAllMaintenance, context) +
            // ImprovementMaintenance only applies inside city territory; ImprovementAllMaintenance applies everywhere.
            (if (tile.getOwner() == currentPlayerCiv) improvement.getMatchingUniques(UniqueType.ImprovementMaintenance, context) else emptySequence())
        for (maintenanceUnique in maintenanceUniques) {
            val amount = maintenanceUnique.params[0].toFloat()
            val statName = Stat.safeValueOf(maintenanceUnique.params[1]) ?: continue
            maintenance.add(statName, -amount)
        }

        for (unique in currentPlayerCiv.getMatchingUniques(UniqueType.RoadMaintenance))
            maintenance.timesInPlace(unique.params[0].toPercent())
        return maintenance
    }

    class ProblemReport {
        var suggestRemoval = false
        var removalImprovement: TileImprovement? = null
        /** `first` is the text, `second` the Civilopedia link */
        val proposedSolutions = mutableSetOf<Pair<String, String?>>()
        fun isEmpty() = proposedSolutions.isEmpty()
        fun isQueueable() = removalImprovement != null && proposedSolutions.size == 1
    }

    fun getProblemReport(improvement: TileImprovement) = getProblemReport(tile, tileWithoutLastTerrain, improvement)
    private fun getProblemReport(tile: Tile, tileWithoutLastTerrain: Tile?, improvement: TileImprovement): ProblemReport? {
        val report = ProblemReport()
        var unbuildableBecause = tile.improvementFunctions.getImprovementBuildingProblems(improvement, unit.cache.state).toSet()
        if (!canReport(unbuildableBecause) && tileWithoutLastTerrain != null) {
            // Try after pretending to have removed the top terrain layer.
            unbuildableBecause = tileWithoutLastTerrain.improvementFunctions.getImprovementBuildingProblems(improvement, unit.cache.state).toSet()
            if (!canReport(unbuildableBecause)) return null
            report.suggestRemoval = true
        }
        if (!canReport(unbuildableBecause)) return null

        with(report) {
            if (suggestRemoval) {
                val removalName = Constants.remove + tile.lastTerrain.name
                removalImprovement = ruleset.tileImprovements[removalName]
                if (removalImprovement != null) {
                    // Check for removals that need a tech that's not yet researched
                    val cannotRemoveReport = getProblemReport(tile, null, removalImprovement!!)
                    if (cannotRemoveReport != null) proposedSolutions.addAll(cannotRemoveReport.proposedSolutions)
                    proposedSolutions.add("${Constants.remove}[${tile.lastTerrain.name}] first" to removalImprovement!!.makeLink())
                }
            }

            if (ImprovementBuildingProblem.MissingTech in unbuildableBecause) {
                val maxEraNumber = if (maxErasForward == Int.MAX_VALUE) Int.MAX_VALUE else currentPlayerCiv.getEraNumber()
                for (tech in improvement.requiredTechnologies(ruleset)) {
                    val techEra = tech?.era(ruleset) ?: continue
                    if (unit.civ.tech.isResearched(tech.name)) continue
                    if (techEra.eraNumber > maxEraNumber) return null
                    proposedSolutions.add("Research [${tech.name}] first" to tech.makeLink())
                }
            }
            if (ImprovementBuildingProblem.NotJustOutsideBorders in unbuildableBecause)
                proposedSolutions.add("Have this tile close to your borders" to null)
            if (ImprovementBuildingProblem.OutsideBorders in unbuildableBecause)
                proposedSolutions.add("Have this tile inside your empire" to null)
            if (ImprovementBuildingProblem.MissingResources in unbuildableBecause) {
                val resources = improvement.getMatchingUniques(UniqueType.ConsumesResources)
                    .filter { currentPlayerCiv.getResourceAmount(it.params[1]) < it.params[0].toInt() }
                    .map { "Acquire more [${it.params[1]}]" to ruleset.tileResources[it.params[1]]?.makeLink() }
                proposedSolutions.addAll(resources)
            }
        }
        return report
    }

}
