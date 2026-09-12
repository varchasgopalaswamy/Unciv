package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.city.CityFocus
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerCityManagementOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(7, "Grassland") }
    private val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = testGame.addCity(player, testGame.getTile(0, 0), initialPopulation = 4)
    private val foreign = testGame.addCity(other, testGame.getTile(6, 0))
    private val operations = PlayerCityManagementOperations(player)
    private val tiles = PlayerCityOperations(player)

    init {
        testGame.gameInfo.currentPlayer = player.civID
        testGame.gameInfo.currentPlayerCiv = player
        city.cityConstructions.addBuilding("University")
        city.cityConstructions.addBuilding("Granary")
        city.manualSpecialists = true
        city.population.specialistAllocations.clear()
        city.clearWorkedTiles()
        player.cache.updateViewableTiles()
        city.cityStats.update()
    }

    @Test
    fun `focus preserves locks and reset citizens clears them`() {
        val tile = city.getWorkableTiles().first { tiles.isAssignableTile(city, it) }
        assertTrue(tiles.trySetWorkedTile(city, tile, true))
        assertTrue(tiles.trySetTileLocked(city, tile, true))
        assertTrue(operations.trySetFocus(city, CityFocus.ProductionFocus))
        assertEquals(CityFocus.ProductionFocus, city.getCityFocus())
        assertTrue(city.isWorked(tile) && tile.isLocked())
        assertFalse(operations.focuses(city).contains(CityFocus.HappinessFocus))
        assertFalse(operations.trySetFocus(city, CityFocus.HappinessFocus))
        assertTrue(operations.tryReassignPopulation(city, resetLocked = true))
        assertTrue(city.lockedTiles.isEmpty())
        assertEquals(0, city.population.getFreePopulation())
        assertStatsCurrent()
    }

    @Test
    fun `avoid growth caps stored food and disabling allows population growth`() {
        assertTrue(operations.trySetAvoidGrowth(city, true))
        val population = city.population.population
        val required = city.population.getFoodToNextPopulation()
        city.population.foodStored = required - 1
        city.population.nextTurn(20)
        assertEquals(population, city.population.population)
        assertEquals(required, city.population.foodStored)
        assertTrue(operations.trySetAvoidGrowth(city, false))
        city.population.nextTurn(1)
        assertEquals(population + 1, city.population.population)
    }

    @Test
    fun `specialist clicks respect capacity and free citizens and update yields`() {
        val science = city.cityStats.currentCityStats.science
        val option = operations.specialists(city).single { it.name == "Scientist" }
        assertEquals(2, option.slots)
        assertTrue(option.assign.available)
        assertFalse(option.unassign.available)
        assertTrue(operations.trySetSpecialist(city, "Scientist", true))
        assertTrue(city.manualSpecialists)
        assertTrue(city.cityStats.currentCityStats.science > science)
        assertTrue(operations.trySetSpecialist(city, "Scientist", true))
        assertFalse(operations.trySetSpecialist(city, "Scientist", true))
        assertFalse(operations.trySetSpecialist(city, "Unknown", true))
        assertTrue(operations.trySetSpecialist(city, "Scientist", false))
        assertTrue(operations.trySetSpecialist(city, "Scientist", false))
        assertFalse(operations.trySetSpecialist(city, "Scientist", false))
        assertTrue(operations.tryReassignPopulation(city, resetLocked = true))
        assertFalse(operations.trySetSpecialist(city, "Scientist", true))
        assertTrue(operations.trySetManualSpecialists(city, false))
        assertFalse(city.manualSpecialists)
        assertEquals(0, city.population.getFreePopulation())
        assertStatsCurrent()
    }

    @Test
    fun `sale refunds native price removes invalid specialists and enforces per turn limit`() {
        assertTrue(operations.trySetSpecialist(city, "Scientist", true))
        assertTrue(operations.trySetSpecialist(city, "Scientist", true))
        val offer = operations.sales(city).single { it.name == "University" }
        val gold = player.gold
        assertTrue(offer.action.available)
        assertTrue(offer.confirmation.contains("University"))
        assertFalse(operations.trySellBuilding(city, offer.name, offer.gold + 1))
        assertEquals(gold, player.gold)
        assertTrue(operations.trySellBuilding(city, offer.name, offer.gold))
        assertEquals(gold + offer.gold, player.gold)
        assertFalse(city.cityConstructions.isBuilt("University"))
        assertEquals(0, city.population.getNewSpecialists()["Scientist"])
        assertEquals(0, city.population.getFreePopulation())
        assertFalse(operations.trySellBuilding(city, "Granary"))
        assertFalse(operations.sales(city).single { it.name == "Granary" }.action.available)
        assertStatsCurrent()
        city.hasSoldBuildingThisTurn = false
        assertTrue(operations.trySellBuilding(city, "Granary"))
    }

    @Test
    fun `free buildings wonders absent buildings and unknown selections cannot be sold`() {
        city.cityConstructions.addBuilding("The Pyramids")
        city.cityConstructions.freeBuildingsProvidedFromThisCity[city.id] = hashSetOf("Granary")
        val gold = player.gold
        for (name in listOf("The Pyramids", "Granary", "Palace", "Factory", "Unknown"))
            assertFalse(name, operations.trySellBuilding(city, name))
        assertEquals(gold, player.gold)
        assertFalse(city.hasSoldBuildingThisTurn)
    }

    @Test
    fun `shared tile transfer changes workers not ownership and clears the donor lock`() {
        val donor = testGame.addCity(player, testGame.getTile(-3, 0))
        donor.clearWorkedTiles()
        player.cache.updateViewableTiles()
        val tile = city.getWorkableTiles().first { it in donor.tilesInRange && tiles.isAssignableTile(donor, it) }
        assertTrue(tiles.trySetWorkedTile(donor, tile, true))
        assertTrue(tiles.trySetTileLocked(donor, tile, true))
        val owner = tile.getCity()
        val beforeDonor = donor.cityStats.currentCityStats.food
        val beforeReceiver = city.cityStats.currentCityStats.food
        assertTrue(operations.reassignTile(city, tile).available)
        assertTrue(operations.tryReassignTile(city, tile))
        assertSame(owner, tile.getCity())
        assertSame(city, tile.getWorkingCity())
        assertFalse(tile.isLocked())
        assertEquals(1, donor.population.getFreePopulation())
        assertEquals(3, city.population.getFreePopulation())
        assertTrue(donor.cityStats.currentCityStats.food < beforeDonor)
        assertTrue(city.cityStats.currentCityStats.food > beforeReceiver)
        assertFalse(operations.tryReassignTile(city, tile))
        assertFalse(operations.tryReassignTile(city, foreign.getCenterTile()))
        assertFalse(operations.tryReassignTile(city, donor.getCenterTile()))
        assertStatsCurrent()
        val view = GameView(testGame.gameInfo, player)
        assertTrue(view.getCityView(donor).tryReassignTile(view.tileMapView.getTile(tile)))
        assertSame(donor, tile.getWorkingCity())
    }

    @Test
    fun `shared tile transfer rejects exhausted receivers blocked tiles and hidden tiles atomically`() {
        val donor = testGame.addCity(player, testGame.getTile(-3, 0))
        donor.clearWorkedTiles()
        player.cache.updateViewableTiles()
        val tile = city.getWorkableTiles().first { it in donor.tilesInRange && tiles.isAssignableTile(donor, it) }
        assertTrue(tiles.trySetWorkedTile(donor, tile, true))
        assertTrue(operations.tryReassignPopulation(city, true))
        assertFalse(operations.tryReassignTile(city, tile))
        assertSame(donor, tile.getWorkingCity())
        city.clearWorkedTiles()
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        other.getDiplomacyManager(player)!!.declareWar()
        val enemy = testGame.addUnit("Warrior", other, tile)
        assertFalse(operations.tryReassignTile(city, tile))
        assertSame(donor, tile.getWorkingCity())
        enemy.destroy()
        player.viewableTiles = player.viewableTiles.filterNot { it === tile }.toSet()
        assertFalse(operations.tryReassignTile(city, tile))
        assertSame(donor, tile.getWorkingCity())
    }

    @Test
    fun `ownership active player spectator and puppet guards also apply through CityView`() {
        fun rejects(ops: PlayerCityManagementOperations) {
            assertFalse(ops.trySetFocus(city, CityFocus.FoodFocus))
            assertFalse(ops.trySetAvoidGrowth(city, true))
            assertFalse(ops.trySetManualSpecialists(city, false))
            assertFalse(ops.trySetSpecialist(city, "Scientist", true))
            assertFalse(ops.tryReassignPopulation(city, true))
            assertFalse(ops.trySellBuilding(city, "University"))
            assertEquals(4, city.population.getFreePopulation())
            assertTrue(city.cityConstructions.isBuilt("University"))
        }
        rejects(PlayerCityManagementOperations(other))
        assertTrue(PlayerCityManagementOperations(other).sales(city).isEmpty())
        assertTrue(PlayerCityManagementOperations(other).specialists(city).isEmpty())
        rejects(PlayerCityManagementOperations(player, spectatorMode = true))
        city.isPuppet = true
        rejects(operations)
        city.isPuppet = false
        testGame.gameInfo.currentPlayerCiv = other
        rejects(operations)
        testGame.gameInfo.currentPlayerCiv = player
        val spectator = GameView(testGame.gameInfo, player, spectatorMode = true).getCityView(city)
        assertFalse(spectator.tryAssignSpecialist("Scientist"))
        assertFalse(spectator.tryToggleAvoidGrowth())
        val view = GameView(testGame.gameInfo, player).getCityView(city)
        assertTrue(view.tryAssignSpecialist("Scientist"))
        assertTrue(view.tryUnassignSpecialist("Scientist"))
        assertFalse(view.tryUnassignSpecialist("Scientist"))
        assertTrue(view.trySetCityFocus(CityFocus.FoodFocus))
        assertTrue(view.tryToggleAvoidGrowth())
        assertTrue(view.tryDisableManualSpecialists())
        assertTrue(view.tryEnableManualSpecialists())
        assertTrue(view.trySellBuilding(testGame.ruleset.buildings.getValue("University")))
    }

    private fun assertStatsCurrent() {
        val before = city.cityStats.currentCityStats.clone()
        city.cityStats.update()
        for (stat in Stat.entries) assertEquals(before[stat], city.cityStats.currentCityStats[stat], 0f)
    }

    @Test
    fun `queries leave the saved game unchanged and return detached choices`() {
        val before = json().toJson(testGame.gameInfo)
        val specialists = operations.specialists(city)
        val sales = operations.sales(city)
        repeat(3) {
            operations.control(city)
            operations.focuses(city)
            operations.specialists(city)
            operations.sales(city)
            city.getWorkableTiles().forEach { operations.reassignTile(city, it) }
        }
        assertEquals(before, json().toJson(testGame.gameInfo))
        assertTrue(operations.trySetSpecialist(city, "Scientist", true))
        assertEquals(0, specialists.single { it.name == "Scientist" }.assigned)
        assertTrue(operations.trySellBuilding(city, "Granary"))
        assertTrue(sales.single { it.name == "Granary" }.action.available)
        try {
            (sales as MutableList).clear()
            fail("Choices must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }
}
