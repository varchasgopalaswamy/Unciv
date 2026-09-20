package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerMovementOrdersTest {
    private val game = TestGame().apply { makeHexagonalMap(10, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val unit = game.addUnit("Warrior", player, game.getTile(0, 0))
    private val destination = game.getTile(7, 0)
    private val operations = PlayerUnitOperations(player)

    init {
        game.addUnit("Settler", player, game.getTile(-8, 0))
        game.addUnit("Settler", other, game.getTile(8, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        for (tile in game.tileMap.values) tile.setExplored(player, true)
        player.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `distant preview is pure and supplies adjacent paths and native turn stops`() {
        val saved = json().toJson(game.gameInfo)
        val route = operations.route(unit, destination)!!
        assertEquals(4, route.turns.size)
        assertEquals(unit.movement.getShortestPath(destination), route.turns.map { it.destination })
        var previous = unit.currentTile
        for (turn in route.turns) {
            for (tile in turn.path) {
                assertTrue(previous.neighbors.any { it === tile })
                previous = tile
            }
            assertSame(turn.destination, previous)
        }
        assertSame(destination, previous)
        assertEquals(saved, json().toJson(game.gameInfo))
        val view = GameView(game.gameInfo, player)
        assertEquals(route, view.getMapUnitView(unit).getMovementRoute(view.tileMapView.getTile(destination)))
    }

    @Test
    fun `persistent native order advances on later turns and clears on arrival`() {
        assertTrue(operations.trySetDestination(unit, destination))
        assertEquals(game.getTile(2, 0), unit.currentTile)
        assertSame(destination, unit.getMovementDestination())
        assertFalse(unit.isIdle())
        repeat(3) {
            UnitTurnManager(unit).startTurn()
            unit.doAction()
        }
        assertSame(destination, unit.currentTile)
        assertFalse(unit.isMoving())
    }

    @Test
    fun `replacement cancellation and current turn moves use the same native action`() {
        assertTrue(operations.trySetDestination(unit, destination))
        assertTrue(operations.canCancelMovement(unit)) // no movement is needed to cancel
        val position = unit.currentTile
        assertTrue(operations.tryCancelMovement(unit))
        assertFalse(operations.tryCancelMovement(unit))
        assertSame(position, unit.currentTile)
        UnitTurnManager(unit).startTurn()
        assertTrue(operations.trySetDestination(unit, game.getTile(-6, 0)))
        assertSame(game.getTile(-6, 0), unit.getMovementDestination())
        val stop = UnitActions.getUnitActions(unit, UnitActionType.StopMovement).first().action!!
        stop()
        assertFalse(unit.isMoving())
        UnitTurnManager(unit).startTurn()
        assertTrue(operations.trySetDestination(unit, destination))
        UnitTurnManager(unit).startTurn()
        assertTrue(operations.tryMove(unit, unit.currentTile.neighbors.first()))
        assertFalse(unit.isMoving())
    }

    @Test
    fun `destination commands reject spectators foreign inactive and exhausted units without mutation`() {
        val foreign = game.addUnit("Warrior", other, game.getTile(9, 0))
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.trySetDestination(foreign, destination))
        assertFalse(PlayerUnitOperations(player, true).trySetDestination(unit, destination))
        assertFalse(PlayerUnitOperations(other).trySetDestination(foreign, destination))
        assertFalse(operations.tryCancelMovement(foreign))
        assertFalse(operations.trySetDestination(unit, unit.currentTile))
        assertEquals(before, json().toJson(game.gameInfo))
        unit.currentMovement = 0f
        assertNull(operations.route(unit, destination))
        assertFalse(operations.trySetDestination(unit, destination))
    }

    @Test
    fun `unknown terrain cannot influence the full path or turn estimates`() {
        for (tile in game.tileMap.values) if (tile !== unit.currentTile) tile.setExplored(player, false)
        player.viewableTiles = setOf(unit.currentTile)
        val before = operations.route(unit, destination)
        for (tile in game.tileMap.values) if (tile !== unit.currentTile) {
            tile.baseTerrain = if (tile.position.x % 2 == 0) "Ocean" else "Mountain"
            tile.setTerrainTransients()
        }
        assertEquals(before, operations.route(unit, destination))
        UncivGame.Current.settings.useAStarPathfinding = true
        assertEquals(before, operations.route(unit, destination))
        assertTrue(operations.trySetDestination(unit, destination))
        assertSame(game.getTile(0, 0), unit.currentTile)
        assertFalse(unit.isMoving())
    }

    @Test
    fun `hidden units cannot change distant preview even after actual path cache is primed`() {
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        player.getDiplomacyManager(other)!!.declareWar()
        player.viewableTiles = setOf(unit.currentTile)
        val before = operations.route(unit, destination)
        val enemy = game.addUnit("Warrior", other, game.getTile(5, 0))
        player.viewableTiles = setOf(unit.currentTile)
        unit.movement.getShortestPath(destination)
        assertEquals(before, operations.route(unit, destination))
        player.viewableTiles = player.viewableTiles + enemy.currentTile
        assertNotEquals(before, operations.route(unit, destination))
    }

    @Test
    fun `automatic order stops for visible enemies but ignores invisible units off its path`() {
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        player.getDiplomacyManager(other)!!.declareWar()
        assertTrue(operations.trySetDestination(unit, destination))
        val origin = unit.currentTile
        val enemy = game.addDefaultMeleeUnitWithUniques(other, game.getTile(2, 1), "Invisible to others")
        UnitTurnManager(unit).startTurn()
        assertFalse(enemy.isVisibleTo(player))
        unit.doAction()
        assertNotSame(origin, unit.currentTile)
        assertTrue(unit.isMoving())
        UnitTurnManager(unit).startTurn()
        val visible = game.addUnit("Warrior", other, game.getTile(4, 1))
        player.viewableTiles = player.viewableTiles + visible.currentTile
        val stopped = unit.currentTile
        unit.doAction()
        assertSame(stopped, unit.currentTile)
        assertFalse(unit.isMoving())
    }
}
