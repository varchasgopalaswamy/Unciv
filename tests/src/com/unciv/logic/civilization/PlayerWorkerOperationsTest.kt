package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.map.mapunit.UnitWorkerRepair
import com.unciv.logic.map.mapunit.WorkerImprovementPicker
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerWorkerOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(8, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = game.addCity(player, game.getTile(0, 0))
    private val tile = game.getTile(1, 0)
    private val worker = game.addUnit("Worker", player, tile)
    private val foreign = game.addUnit("Worker", other, game.getTile(6, 0))
    private val operations = PlayerWorkerOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.tech.addTechnology("Agriculture")
    }

    @Test
    fun `orders revalidate player ownership movement and current tile`() {
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.tryStartJob(foreign, "Farm"))
        assertFalse(PlayerWorkerOperations(other).tryStartJob(foreign, "Road"))
        assertFalse(PlayerWorkerOperations(player, true).tryStartJob(worker, "Farm"))
        assertFalse(operations.tryStartJob(worker, "Unrecognized improvement"))
        assertFalse(operations.tryStartJob(worker, "Academy"))
        assertFalse(operations.tryStopJob(worker))
        assertEquals(before, json().toJson(game.gameInfo))
        worker.currentMovement = 0f
        assertFalse(operations.tryStartJob(worker, "Farm"))
        assertNull(UnitActions.getUnitActions(worker, UnitActionType.ConstructImprovement).single().action)
        worker.currentMovement = 1f
        assertNotNull(UnitActions.getUnitActions(worker, UnitActionType.ConstructImprovement).single().action)
        assertTrue(operations.tryStartJob(worker, "Farm"))
        assertEquals("Farm", tile.improvementInProgress)
        assertEquals(1f, worker.currentMovement, 0f)
        worker.destroy()
        assertFalse(operations.tryStopJob(worker))
    }

    @Test
    fun `starting changing continuing and stopping preserve picker semantics`() {
        player.tech.addTechnology("The Wheel")
        worker.action = "Sleep"
        assertTrue(operations.tryStartJob(worker, "Farm"))
        assertNull(worker.action)
        assertEquals(game.ruleset.tileImprovements.getValue("Farm").getTurnsToBuild(player, worker), tile.turnsToImprovement)
        assertFalse(tile.doWorkerTurn(worker))
        val remaining = tile.turnsToImprovement
        assertTrue(operations.tryStartJob(worker, "Farm"))
        assertEquals(remaining, tile.turnsToImprovement)
        assertTrue(operations.tryStartJob(worker, "Road"))
        assertEquals(listOf("Road"), operations.queue(worker).map { it.name })
        assertTrue(operations.tryStopJob(worker))
        assertNull(tile.improvementInProgress)
        assertFalse(operations.tryStopJob(worker))
    }

    @Test
    fun `picker discovers missing tech borders and resource dependent jobs without revealing hidden resources`() {
        game.setTileFeatures(tile.position, "Hill")
        var mine = WorkerImprovementPicker(worker).options().single { it.improvement.name == "Mine" }
        assertEquals(setOf("Research [Mining] first"), mine.report.proposedSolutions.map { it.first }.toSet())
        assertFalse(operations.tryStartJob(worker, "Mine"))
        player.tech.addTechnology("Mining")
        mine = WorkerImprovementPicker(worker).options().single { it.improvement.name == "Mine" }
        assertTrue(mine.report.isEmpty())
        game.setTileFeatures(tile.position)
        tile.tileResource = game.ruleset.tileResources.getValue("Iron")
        assertFalse(WorkerImprovementPicker(worker).options().any { it.improvement.name == "Mine" })
        player.tech.addTechnology("Iron Working")
        assertTrue(operations.canStartJob(worker, "Mine"))
        val unownedWorker = game.addUnit("Worker", player, game.getTile(4, 0))
        val farm = WorkerImprovementPicker(unownedWorker).options().single { it.improvement.name == "Farm" }
        assertTrue(farm.report.proposedSolutions.any { it.first == "Have this tile inside your empire" })
        assertFalse(operations.tryStartJob(unownedWorker, "Farm"))
        player.tech.addTechnology("The Wheel")
        assertTrue(operations.tryStartJob(unownedWorker, "Road"))
    }

    @Test
    fun `feature removal and final improvement use the same two job queue as picker`() {
        game.setTileFeatures(tile.position, "Forest")
        val unavailable = WorkerImprovementPicker(worker).options().single { it.improvement.name == "Farm" }
        assertFalse(unavailable.report.isQueueable())
        assertFalse(operations.tryStartJob(worker, "Farm"))
        player.tech.addTechnology("Mining")
        val option = WorkerImprovementPicker(worker).options().single { it.improvement.name == "Farm" }
        assertTrue(option.report.isQueueable())
        assertEquals("Remove Forest", option.report.removalImprovement?.name)
        assertTrue(operations.tryStartJob(worker, "Farm"))
        assertEquals(listOf("Remove Forest", "Farm"), operations.queue(worker).map { it.name })
        while (tile.improvementInProgress == "Remove Forest") tile.doWorkerTurn(worker)
        assertFalse("Forest" in tile.terrainFeatures)
        assertEquals("Farm", tile.improvementInProgress)
        while (tile.improvementInProgress != null) tile.doWorkerTurn(worker)
        assertEquals("Farm", tile.improvement)
    }

    @Test
    fun `road removal previews do not modify the road owners records`() {
        val neutral = game.getTile(4, 0)
        neutral.setRoadStatus(RoadStatus.Road, player)
        assertTrue(neutral.position in player.neutralRoads)
        val before = json().toJson(game.gameInfo)
        neutral.stats.getStatDiffForImprovement(game.ruleset.tileImprovements.getValue("Remove Road"), player, null)
        assertEquals(before, json().toJson(game.gameInfo))
        neutral.setImprovement("Remove Road", player)
        assertFalse(neutral.position in player.neutralRoads)
    }

    @Test
    fun `roads removals and repairs complete through normal worker turns`() {
        player.tech.addTechnology("The Wheel")
        assertTrue(operations.tryStartJob(worker, "Road"))
        while (tile.improvementInProgress != null) tile.doWorkerTurn(worker)
        assertEquals(RoadStatus.Road, tile.roadStatus)
        assertTrue(operations.tryStartJob(worker, "Remove Road"))
        while (tile.improvementInProgress != null) tile.doWorkerTurn(worker)
        assertEquals(RoadStatus.None, tile.roadStatus)
        tile.setImprovement("Farm", player)
        tile.setPillaged()
        val uiAction = UnitActions.getUnitActions(worker, UnitActionType.Repair).single()
        assertEquals(UnitWorkerRepair.canRepair(worker), uiAction.action != null)
        uiAction.action!!.invoke()
        val afterUi = json().toJson(game.gameInfo)
        tile.stopWorkingOnImprovement()
        assertTrue(operations.tryStartJob(worker, Constants.repair))
        assertEquals(afterUi, json().toJson(game.gameInfo))
        assertFalse(operations.tryStartJob(worker, Constants.repair))
        while (tile.improvementInProgress != null) tile.doWorkerTurn(worker)
        assertFalse(tile.isPillaged())
        assertEquals("Farm", tile.improvement)
    }

    @Test
    fun `repair respects enemy territory and maintains previous construction ordering`() {
        tile.setImprovement("Farm", player)
        tile.setPillaged()
        player.tech.addTechnology("The Wheel")
        assertTrue(operations.tryStartJob(worker, "Road"))
        assertTrue(operations.tryStartJob(worker, Constants.repair))
        assertEquals(listOf("Road", Constants.repair), operations.queue(worker).map { it.name })
        assertTrue(operations.tryStopJob(worker))
        game.addCity(other, game.getTile(6, 0))
        val enemyTile = game.getTile(5, 0)
        enemyTile.setImprovement("Farm", other)
        enemyTile.setPillaged()
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        player.getDiplomacyManager(other)!!.declareWar()
        val invadingWorker = game.addUnit("Worker", player, enemyTile)
        assertFalse(operations.tryStartJob(invadingWorker, Constants.repair))
        assertNull(UnitActions.getUnitActions(invadingWorker, UnitActionType.Repair).single().action)
    }

    @Test
    fun `picker and stat discovery are read only and city centers cannot receive worker jobs`() {
        val before = json().toJson(game.gameInfo)
        val picker = WorkerImprovementPicker(worker)
        for (option in picker.options()) {
            picker.getMaintenance(option.improvement)
            tile.stats.getStatDiffForImprovement(option.improvement, player, city)
        }
        assertEquals(before, json().toJson(game.gameInfo))
        val centerWorker = game.addUnit("Worker", player, city.getCenterTile())
        assertFalse(operations.tryStartJob(centerWorker, "Farm"))
        assertFalse(WorkerImprovementPicker.canOpen(centerWorker))
        tile.improvementFunctions.markForCreatesOneImprovement("Farm")
        assertFalse(operations.tryStartJob(worker, "Farm"))
        assertFalse(operations.tryStopJob(worker))
    }
}
