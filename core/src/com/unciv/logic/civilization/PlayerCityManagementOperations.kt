package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import java.util.Collections

/** City-screen citizen controls and building sales, revalidated under the game monitor.
 * Queries never reassign citizens or refresh statistics. UI callers must also respect
 * their local input-disabled state. A shared tile changes working city, not ownership.
 */
class PlayerCityManagementOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Action(val available: Boolean, val unavailableReasons: List<String>)
    data class SpecialistOption(val name: String, val assigned: Int, val slots: Int, val assign: Action, val unassign: Action)
    data class SaleOption(
        val name: String, val gold: Int, val maintenance: Int, val description: String,
        val action: Action, val confirmation: String,
    )

    @Readonly
    private fun owns(city: City): Boolean = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ } && city.civ === civ && civ.cities.any { it === city }

    @Readonly
    private fun reasons(city: City): List<String> {
        if (!owns(city)) return listOf("This city is not controlled by the player")
        @LocalState val result = ArrayList<String>()
        if (!PlayerOperations(civ, spectatorMode).canAct()) result.add("This player cannot act now")
        if (city.isPuppet) result.add("Cannot manage citizens or sell buildings in a puppet city")
        return result
    }

    @Readonly fun control(city: City): Action = action(reasons(city))

    @Readonly
    fun focuses(city: City): List<CityFocus> = if (!owns(city)) emptyList() else frozen(CityFocus.entries.filter {
        it.tableEnabled && (it != CityFocus.FaithFocus || civ.gameInfo.isReligionEnabled())
    })

    fun trySetFocus(city: City, focus: CityFocus): Boolean = synchronized(civ.gameInfo) {
        if (!control(city).available || focus !in focuses(city)) return@synchronized false
        city.setCityFocus(focus)
        city.reassignPopulation()
        civ.updateStatsForNextTurn()
        true
    }

    fun trySetAvoidGrowth(city: City, enabled: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!control(city).available) return@synchronized false
        city.avoidGrowth = enabled
        city.reassignPopulation()
        civ.updateStatsForNextTurn()
        true
    }

    fun trySetManualSpecialists(city: City, enabled: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!control(city).available) return@synchronized false
        city.manualSpecialists = enabled
        if (!enabled) city.reassignPopulation()
        civ.updateStatsForNextTurn()
        true
    }

    /** Reset Citizens clears tile locks; other callers may request ordinary reassignment. */
    fun tryReassignPopulation(city: City, resetLocked: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!control(city).available) return@synchronized false
        city.reassignPopulation(resetLocked)
        civ.updateStatsForNextTurn()
        true
    }

    @Readonly
    private fun specialistReasons(city: City, name: String, assign: Boolean): List<String> {
        @LocalState val result = reasons(city).toMutableList()
        if (!owns(city)) return result
        val slots = city.population.getMaxSpecialists()[name]
        if (name !in civ.gameInfo.ruleset.specialists || slots <= 0) result.add("No slots for this specialist")
        if (assign) {
            if (city.population.getNewSpecialists()[name] >= slots) result.add("All specialist slots are occupied")
            if (city.population.getFreePopulation() <= 0) result.add("Unassign a citizen first")
        } else if (city.population.getNewSpecialists()[name] <= 0) result.add("No assigned specialist to remove")
        return result
    }

    @Readonly
    fun specialists(city: City): List<SpecialistOption> {
        if (!owns(city)) return emptyList()
        return frozen(city.population.getMaxSpecialists().entries.filter { it.key in civ.gameInfo.ruleset.specialists }
            .sortedBy { it.key }.map { (name, slots) ->
                SpecialistOption(name, city.population.getNewSpecialists()[name], slots,
                    action(specialistReasons(city, name, true)), action(specialistReasons(city, name, false)))
            })
    }

    /** One click in the specialist picker. Adding never steals a citizen from a tile. */
    fun trySetSpecialist(city: City, name: String, assign: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (specialistReasons(city, name, assign).isNotEmpty()) return@synchronized false
        city.population.specialistAllocations.add(name, if (assign) 1 else -1)
        city.manualSpecialists = true
        city.cityStats.update()
        civ.updateStatsForNextTurn()
        true
    }

    @Readonly
    private fun saleReasons(city: City, building: Building): List<String> {
        @LocalState val result = reasons(city).toMutableList()
        if (!owns(city)) return result
        if (!city.cityConstructions.isBuilt(building.name)) result.add("This building is not present")
        if (!building.isSellable()) result.add("This building cannot be sold")
        if (civ.civConstructions.hasFreeBuilding(city, building)) result.add("Free buildings cannot be sold")
        if (city.hasSoldBuildingThisTurn && !civ.gameInfo.gameParameters.godMode) result.add("Already sold a building in this city this turn")
        return result
    }

    fun sales(city: City): List<SaleOption> {
        if (!owns(city)) return emptyList()
        return frozen(city.cityConstructions.getBuiltBuildings().sortedBy { it.name }.map { building ->
            SaleOption(building.name, city.getGoldForSellingBuilding(building.name), building.maintenance,
                building.getShortDescription(multiline = true), action(saleReasons(city, building)),
                "Are you sure you want to sell this [${building.name}]?")
        }.toList())
    }

    fun trySellBuilding(city: City, name: String, expectedGold: Int? = null): Boolean = synchronized(civ.gameInfo) {
        if (!owns(city)) return@synchronized false
        val building = civ.gameInfo.ruleset.buildings[name] ?: return@synchronized false
        if (saleReasons(city, building).isNotEmpty() || expectedGold != null && expectedGold != city.getGoldForSellingBuilding(name))
            return@synchronized false
        city.sellBuilding(building)
        civ.updateStatsForNextTurn()
        true
    }

    @Readonly
    fun reassignTile(city: City, tile: Tile): Action {
        @LocalState val result = reasons(city).toMutableList()
        if (!owns(city) || tile !in city.tilesInRange || !tile.isVisible(civ) || tile.getOwner() !== civ)
            return action(listOf("This tile is not available to this city"))
        val donor = tile.getWorkingCity()
        if (donor == null || donor === city || !owns(donor)) result.add("The tile must be worked by another owned city")
        else if (!PlayerCityOperations(civ, spectatorMode).canSetWorkedTile(donor, tile, false)) result.add("The working city cannot release this tile")
        if (tile.isCityCenter()) result.add("City centers cannot be reassigned")
        if (tile.isBlockaded()) result.add("The tile is blockaded")
        if (tile.stats.getTileStats(city, civ).isEmpty()) result.add("The tile provides no yields")
        if (city.population.getFreePopulation() <= 0) result.add("Unassign a citizen in the receiving city first")
        return action(result)
    }

    /** Atomic equivalent of unworking in the donor city, then working in the recipient. */
    fun tryReassignTile(city: City, tile: Tile): Boolean = synchronized(civ.gameInfo) {
        if (!reassignTile(city, tile).available) return@synchronized false
        val donor = tile.getWorkingCity()!!
        check(donor.stopWorkingTile(tile))
        check(city.workTile(tile))
        donor.cityStats.update()
        city.cityStats.update()
        civ.updateStatsForNextTurn()
        true
    }

    @Readonly private fun action(reasons: List<String>): Action = Action(reasons.isEmpty(), frozen(reasons))
    @Readonly private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
