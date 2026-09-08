package com.unciv.logic.civilization

import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerCityOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(6) }
    private val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = testGame.addCity(player, testGame.getTile(0, 0))
    private val foreign = testGame.addCity(other, testGame.getTile(5, 0))
    private val operations = PlayerCityOperations(player)

    init {
        testGame.gameInfo.currentPlayer = player.civID
        testGame.gameInfo.currentPlayerCiv = player
    }

    @Test
    fun `assignment follows city map population and locking semantics`() {
        city.clearWorkedTiles()
        val tiles = city.getWorkableTiles().filter { operations.isAssignableTile(city, it) }.toList()
        assertTrue(tiles.size >= 2)
        val first = tiles[0]
        val second = tiles[1]
        assertTrue(operations.trySetWorkedTile(city, first, true))
        assertEquals(0, city.population.getFreePopulation())
        assertFalse(operations.trySetWorkedTile(city, second, true))
        assertFalse(city.isWorked(second))
        assertTrue(operations.trySetTileLocked(city, first, true))
        assertTrue(first.isLocked())
        assertFalse(operations.trySetTileLocked(city, first, true))
        assertTrue(operations.trySetWorkedTile(city, first, false))
        assertFalse(first.isLocked())
        assertEquals(1, city.population.getFreePopulation())
        assertTrue(operations.trySetWorkedTile(city, second, true))
        val updated = city.cityStats.currentCityStats.clone()
        city.cityStats.update()
        for (stat in Stat.entries) assertEquals(updated[stat], city.cityStats.currentCityStats[stat], 0f)
    }

    @Test
    fun `invalid and foreign tiles are rejected without population changes`() {
        city.clearWorkedTiles()
        for (tile in listOf(city.getCenterTile(), foreign.getCenterTile(), testGame.getTile(-6, 0))) {
            assertFalse(operations.trySetWorkedTile(city, tile, true))
            assertFalse(operations.trySetTileLocked(city, tile, true))
        }
        assertEquals(1, city.population.getFreePopulation())
        val tile = city.getWorkableTiles().first { operations.isAssignableTile(city, it) }
        tile.baseTerrain = "Mountain"
        tile.setTerrainTransients()
        assertFalse(operations.trySetWorkedTile(city, tile, true))
    }

    @Test
    fun `blockaded tiles and citizens assigned to another own city cannot be taken`() {
        city.clearWorkedTiles()
        val adjacentCity = testGame.addCity(player, testGame.getTile(-3, 0))
        adjacentCity.clearWorkedTiles()
        val shared = city.getWorkableTiles().first {
            it in adjacentCity.tilesInRange && operations.isAssignableTile(city, it)
        }
        assertTrue(operations.trySetWorkedTile(adjacentCity, shared, true))
        assertFalse(operations.trySetWorkedTile(city, shared, true))
        assertTrue(adjacentCity.isWorked(shared))
        assertFalse(city.isWorked(shared))
        assertTrue(operations.trySetWorkedTile(adjacentCity, shared, false))
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        other.getDiplomacyManager(player)!!.declareWar()
        testGame.addUnit("Warrior", other, shared)
        assertTrue(shared.isBlockaded())
        assertFalse(operations.trySetWorkedTile(city, shared, true))
        assertEquals(1, city.population.getFreePopulation())
    }

    @Test
    fun `queue edits preserve stored work duplicate units and perpetual entries`() {
        val queue = city.cityConstructions
        queue.constructionQueue = arrayListOf("Worker", "Scout", "Worker", PerpetualConstruction.Idle.name)
        queue.inProgressConstructions["Worker"] = 12
        assertTrue(operations.tryMoveConstruction(city, 2, 0))
        assertEquals(listOf("Worker", "Worker", "Scout", PerpetualConstruction.Idle.name), queue.constructionQueue)
        assertTrue(operations.tryMoveConstruction(city, 3, 1))
        assertEquals(listOf("Worker", PerpetualConstruction.Idle.name, "Worker", "Scout"), queue.constructionQueue)
        assertEquals(12, queue.getWorkDone("Worker"))
        assertTrue(operations.tryRemoveConstruction(city, 2))
        assertEquals(listOf("Worker", PerpetualConstruction.Idle.name, "Scout"), queue.constructionQueue)
        val before = queue.constructionQueue.toList()
        for (index in listOf(-1, before.size, Int.MAX_VALUE)) {
            assertFalse(operations.tryRemoveConstruction(city, index))
            assertFalse(operations.tryMoveConstruction(city, index, 0))
            assertFalse(operations.tryMoveConstruction(city, 0, index))
        }
        assertFalse(operations.tryMoveConstruction(city, 0, 0))
        assertEquals(before, queue.constructionQueue)
        while (queue.constructionQueue.size > 1) assertTrue(operations.tryRemoveConstruction(city, 0))
        assertTrue(operations.tryRemoveConstruction(city, 0))
        assertEquals(listOf(PerpetualConstruction.Idle.name), queue.constructionQueue)
        assertEquals(12, queue.getWorkDone("Worker"))
    }

    @Test
    fun `UI routes use the same citizen and queue validation`() {
        city.clearWorkedTiles()
        val view = GameView(testGame.gameInfo, player)
        val cityView = view.getCityView(city)
        val tiles = city.getWorkableTiles().filter { operations.isAssignableTile(city, it) }.toList()
        assertTrue(cityView.tryWorkTile(view.tileMapView.getTile(tiles[0])))
        assertFalse(cityView.tryWorkTile(view.tileMapView.getTile(tiles[1])))
        assertTrue(cityView.tryLockTile(view.tileMapView.getTile(tiles[0])))
        assertTrue(cityView.tryStopWorkingTile(view.tileMapView.getTile(tiles[0])))
        city.cityConstructions.constructionQueue = arrayListOf("Worker", "Scout")
        assertEquals(0, cityView.tryRaisePriority(1))
        assertEquals(listOf("Scout", "Worker"), city.cityConstructions.constructionQueue)
        assertEquals(1, cityView.tryLowerPriority(0))
        assertTrue(cityView.tryRemoveFromQueue(1, false))
        assertFalse(cityView.tryRemoveFromQueue(1, false))
    }

    @Test
    fun `ownership active player spectator and puppet guards precede mutation`() {
        city.clearWorkedTiles()
        city.cityConstructions.constructionQueue = arrayListOf("Worker", "Scout")
        val tile = city.getWorkableTiles().first { operations.isAssignableTile(city, it) }
        fun verifyRejected(ops: PlayerCityOperations) {
            assertFalse(ops.trySetWorkedTile(city, tile, true))
            assertFalse(ops.tryRemoveConstruction(city, 0))
            assertFalse(ops.tryMoveConstruction(city, 0, 1))
            assertEquals(listOf("Worker", "Scout"), city.cityConstructions.constructionQueue)
            assertEquals(1, city.population.getFreePopulation())
        }
        verifyRejected(PlayerCityOperations(other))
        verifyRejected(PlayerCityOperations(player, spectatorMode = true))
        city.isPuppet = true
        verifyRejected(operations)
        city.isPuppet = false
        testGame.gameInfo.currentPlayerCiv = other
        verifyRejected(operations)
        testGame.gameInfo.currentPlayerCiv = player
        val spectator = GameView(testGame.gameInfo, player, spectatorMode = true)
        assertFalse(spectator.getCityView(city).tryWorkTile(spectator.tileMapView.getTile(tile)))
        assertFalse(spectator.getCityView(city).tryRemoveFromQueue(0, false))
    }
}
