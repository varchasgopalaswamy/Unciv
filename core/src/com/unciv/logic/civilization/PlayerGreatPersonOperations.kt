package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import java.util.Collections
import kotlin.math.min

/** Human Great Person abilities, revalidated under the game monitor at execution.
 *
 * Abilities operate on the unit's current tile. Options describe the same effects
 * used by the desktop, including consumption and instant improvement replacement.
 * The associated unique is for in-process UI callers, not a serialization surface.
 */
class PlayerGreatPersonOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Option(
        val name: String,
        val kind: UnitActionType,
        val title: String,
        val description: String,
        val available: Boolean,
        val reasons: List<String>,
        val consumesUnit: Boolean,
        val science: Int? = null,
        val culture: Int? = null,
        val production: Int? = null,
        val goldenAgeTurns: Int? = null,
        val improvement: String? = null,
        internal val unique: Unique,
    )

    private data class Action(val option: Option, val run: () -> Unit)

    private fun canInspect(unit: MapUnit) = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ } &&
        PlayerUnitOperations(civ).owns(unit) && unit.isGreatPerson()

    fun options(unit: MapUnit): List<Option> = immutable(actions(unit).map { it.option })

    fun tryActivate(unit: MapUnit, name: String): Boolean = synchronized(civ.gameInfo) {
        val action = actions(unit).singleOrNull { it.option.name == name && it.option.available }
            ?: return@synchronized false
        action.run()
        civ.updateStatsForNextTurn()
        true
    }

    private fun actions(unit: MapUnit): List<Action> {
        if (!canInspect(unit)) return emptyList()
        val tile = unit.currentTile
        val result = ArrayList<Action>()
        fun reasons() = mutableListOf<String>().apply {
            if (!PlayerOperations(civ, spectatorMode).canAct()) add("This player cannot act now")
            if (!unit.hasMovement()) add("No movement remaining")
        }
        fun add(name: String, kind: UnitActionType, unique: Unique, title: String, description: String,
                reasons: List<String>, consumes: Boolean = true, science: Int? = null, culture: Int? = null,
                production: Int? = null, goldenAgeTurns: Int? = null, improvement: String? = null, run: () -> Unit) {
            result.add(Action(Option(name, kind, title, description, reasons.isEmpty(), immutable(reasons.distinct()),
                consumes, science, culture, production, goldenAgeTurns, improvement, unique), run))
        }

        for ((index, unique) in unit.getMatchingUniques(UniqueType.CanHurryResearch).withIndex()) {
            val reasons = reasons()
            val technology = civ.tech.currentTechnology()
            if (technology == null) reasons.add("Select a technology to research first")
            else if (technology.hasUnique(UniqueType.CannotBeHurried)) reasons.add("This technology cannot be hurried")
            val science = civ.tech.getScienceFromGreatScientist()
            add("research${index + 1}", UnitActionType.HurryResearch, unique, "Hurry Research (+[$science] Science)",
                "Add [$science] Science to the current technology. Research overflow limits still apply. Consumes this unit.",
                reasons, science = science) {
                civ.tech.addScience(science)
                unit.consume()
            }
        }
        for ((index, unique) in unit.getMatchingUniques(UniqueType.CanHurryPolicy).withIndex()) {
            val culture = civ.policies.getCultureFromGreatWriter()
            add("culture${index + 1}", UnitActionType.HurryPolicy, unique, "Hurry Policy (+[$culture] Culture)",
                "Add [$culture] Culture. Any policy choice remains yours. Consumes this unit.", reasons(), culture = culture) {
                civ.policies.addCulture(culture)
                unit.consume()
            }
        }
        for (type in listOf(UniqueType.CanSpeedupConstruction, UniqueType.CanSpeedupWonderConstruction)) {
            for ((index, unique) in unit.getMatchingUniques(type).withIndex()) {
                val wonder = type == UniqueType.CanSpeedupWonderConstruction
                val kind = if (wonder) UnitActionType.HurryWonder else UnitActionType.HurryBuilding
                val city = tile.getCity()?.takeIf { tile.isCityCenter() }
                val construction = city?.cityConstructions
                val reasons = reasons()
                if (construction == null) reasons.add("Move to a city center first")
                else {
                    if (wonder && !construction.isBuildingWonder()) reasons.add("The city must be constructing a wonder")
                    if (!wonder && construction.getCurrentConstruction() !is Building) reasons.add("The city must be constructing a building")
                    if (!construction.canBeHurried()) reasons.add("This construction cannot be hurried")
                }
                val maximum = city?.let { ((300 + 30 * it.population.population) * civ.gameInfo.speed.productionCostModifier).toInt() }
                val production = if (maximum == null) null else if (wonder) maximum
                    else min(maximum, construction!!.getRemainingWork(construction.currentConstructionName()) - 1).coerceAtLeast(0)
                if (production == 0) reasons.add("No production can be added; ordinary building hurry leaves at least one production remaining")
                val title = if (production == null) kind.value else "${kind.value} (+[$production] Production)"
                add("${if (wonder) "wonder" else "construction"}${index + 1}", kind, unique, title,
                    if (wonder) "Add production to the city's current wonder. Consumes this unit."
                    else "Add production to the city's current building, leaving at least one production remaining. Consumes this unit.",
                    reasons, production = production) {
                    construction!!.addProductionPoints(production!!)
                    construction.constructIfEnough()
                    unit.consume()
                }
            }
        }
        for ((index, unique) in unit.getMatchingUniques(UniqueType.CanTradeWithCityStateForGoldAndInfluence).withIndex()) {
            val recipient = tile.owningCity?.civ
            val reasons = reasons()
            if (recipient?.isCityState != true || recipient === civ) reasons.add("Move into a foreign city-state's territory first")
            else if (!civ.knows(recipient)) reasons.add("Meet the city-state first")
            else if (recipient.isAtWarWith(civ)) reasons.add("You must be at peace with the city-state")
            var gold = (350 + 50 * civ.getEraNumber()) * civ.gameInfo.speed.goldCostModifier
            for (modifier in unit.getMatchingUniques(UniqueType.PercentGoldFromTradeMissions, checkCivInfoUniques = true))
                gold *= modifier.params[0].toPercent()
            val earned = gold.toInt()
            val influence = unique.params[0].toFloat()
            add("mission${index + 1}", UnitActionType.ConductTradeMission, unique, UnitActionType.ConductTradeMission.value,
                "Gain [$earned] Gold and [${influence.tr()}] Influence with the city-state. Consumes this unit.", reasons) {
                civ.addGold(earned)
                recipient!!.getDiplomacyManager(civ)!!.addInfluence(influence)
                civ.addNotification("Your trade mission to [$recipient] has earned you [${earned.tr()}] gold and [${influence.tr()}] influence!",
                    NotificationCategory.General, recipient.civName, NotificationIcon.Gold, NotificationIcon.Culture)
                unit.consume()
            }
        }
        val goldenAges = unit.getMatchingUniques(UniqueType.OneTimeEnterGoldenAgeTurns)
            .filter { it.hasModifier(UniqueType.UnitActionConsumeUnit) }
        for ((index, unique) in goldenAges.withIndex()) {
            val reasons = reasons()
            if (!UnitActionModifiers.canActivateSideEffects(unit, unique)) reasons.add("The ability's movement, use or resource requirements are not met")
            val trigger = UniqueTriggerActivation.getTriggerFunction(unique, civ, unit = unit, tile = tile)
            if (trigger == null) reasons.add("The ability cannot be activated here")
            val turns = civ.goldenAges.calculateGoldenAgeLength(unique.params[0].toInt()) * unique.getUniqueMultiplier(unit.cache.state)
            val title = UnitActionModifiers.actionTextWithSideEffects("Empire enters a [$turns]-turn Golden Age", unique, unit)
            add("goldenAge${index + 1}", UnitActionType.TriggerUnique, unique, title,
                "Add [$turns] turns to your Golden Age. Consumes this unit.", reasons, goldenAgeTurns = turns) {
                repeat(unique.getUniqueMultiplier(unit.cache.state)) { trigger!!.invoke() }
                UnitActionModifiers.activateSideEffects(unit, unique)
            }
        }
        val context = GameContext(civInfo = civ, unit = unit, tile = tile)
        var improvementIndex = 0
        for (unique in UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.ConstructImprovementInstantly)) {
            for (improvement in tile.ruleset.tileImprovements.values.filter { it.matchesFilter(unique.params[0], context) }) {
                val reasons = reasons()
                for (problem in tile.improvementFunctions.getImprovementBuildingProblems(improvement, unit.cache.state)) {
                    reasons.add(when (problem) {
                        ImprovementBuildingProblem.OutsideBorders -> "The unit must be in your territory, or just outside it if this improvement permits it"
                        ImprovementBuildingProblem.MissingTech -> "Requires [${improvement.techRequired}]"
                        ImprovementBuildingProblem.MissingResources -> "Not enough resources"
                        ImprovementBuildingProblem.Other -> if (tile.isCityCenter()) "Improvements cannot replace a city center"
                            else if (tile.improvement == improvement.name) "This tile already has the improvement"
                            else "This improvement cannot be built on the current terrain"
                        else -> "The improvement's requirements are not met"
                    })
                }
                if (tile.isMarkedForCreatesOneImprovement()) reasons.add("A construction has reserved this tile for another improvement")
                val resources = civ.getCivResourcesByName()
                if (improvement.getMatchingUniques(UniqueType.ConsumesResources).any { (resources[it.params[1]] ?: 0) < it.params[0].toInt() })
                    reasons.add("Not enough resources")
                if (!UnitActionModifiers.canActivateSideEffects(unit, unique)) reasons.add("The ability's movement, use or resource requirements are not met")
                val title = UnitActionModifiers.actionTextWithSideEffects("Create [${improvement.name}]", unique, unit)
                add("improvement${++improvementIndex}", UnitActionType.CreateImprovement, unique, title,
                    improvement.getDescription(tile.ruleset), reasons, consumes = unique.hasModifier(UniqueType.UnitActionConsumeUnit), improvement = improvement.name) {
                    tile.setImprovement(improvement, civ, unit)
                    civ.cache.updateViewableTiles()
                    UnitActionModifiers.activateSideEffects(unit, unique)
                }
            }
        }
        return result
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
