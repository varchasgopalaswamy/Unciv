package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.mapunit.UnitSettlement
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerUnitOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(8) }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val settler = game.addUnit("Settler", player, game.getTile(0, 0))
    private val foreign = game.addUnit("Settler", other, game.getTile(6, 0))
    private val operations = PlayerUnitOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
    }

    @Test
    fun `movement validates actor ownership movement and destination`() {
        val destination = game.getTile(1, 0)
        assertFalse(operations.tryMove(foreign, destination))
        assertFalse(PlayerUnitOperations(other).tryMove(foreign, game.getTile(5, 0)))
        assertFalse(PlayerUnitOperations(player, true).tryMove(settler, destination))
        assertFalse(operations.tryMove(settler, game.getTile(7, 0)))
        assertFalse(operations.tryMove(settler, settler.currentTile))
        settler.currentMovement = 0f
        assertTrue(operations.moves(settler).isEmpty())
        assertFalse(operations.tryMove(settler, destination))
        settler.currentMovement = 2f
        assertTrue(operations.tryMove(settler, destination))
        assertEquals(destination, settler.currentTile)
        assertEquals(1f, settler.currentMovement, 0.001f)
    }

    @Test
    fun `preview does not inspect unexplored terrain or depend on pathfinding setting`() {
        val hidden = game.getTile(2, 0)
        hidden.setExplored(player, false)
        player.viewableTiles = player.viewableTiles - hidden
        val before = operations.moves(settler)
        hidden.baseTerrain = "Mountain"
        hidden.setTerrainTransients()
        assertEquals(before, operations.moves(settler))
        UncivGame.Current.settings.useAStarPathfinding = !UncivGame.Current.settings.useAStarPathfinding
        assertEquals(before, operations.moves(settler))
        assertTrue(operations.tryMove(settler, hidden))
        assertNotEquals(hidden, settler.currentTile)
        assertFalse(settler.currentTile.isImpassible())
    }

    @Test
    fun `preview ignores unseen enemy units and their zones of control`() {
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        other.getDiplomacyManager(player)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(player, true)
        player.viewableTiles = setOf(settler.currentTile)
        val before = operations.moves(settler)
        val enemy = game.addUnit("Warrior", other, game.getTile(1, 0))
        player.viewableTiles = setOf(settler.currentTile)
        assertFalse(enemy.isVisibleTo(player))
        assertEquals(before, operations.moves(settler))
        player.viewableTiles = player.viewableTiles + enemy.currentTile
        assertNotEquals(before, operations.moves(settler))
        assertFalse(operations.moves(settler).any { it.destination == enemy.currentTile })

        val enemyTile = enemy.currentTile
        enemy.destroy()
        val invisible = game.addDefaultMeleeUnitWithUniques(other, enemyTile, "Invisible to others")
        player.viewableTiles = player.viewableTiles + enemyTile
        assertFalse(invisible.isVisibleTo(player))
        assertEquals(before, operations.moves(settler))
        player.viewableInvisibleUnitsTiles = setOf(enemyTile)
        assertNotEquals(before, operations.moves(settler))
    }

    @Test
    fun `planning retains known invisible friendly units and rejects disconnected paths`() {
        val soldier = game.addUnit("Warrior", player, settler.currentTile)
        val blocker = game.addDefaultMeleeUnitWithUniques(player, game.getTile(1, 0), "Invisible to others")
        assertFalse(operations.moves(soldier).any { it.destination === blocker.currentTile })
        val original = json().toJson(game.gameInfo)
        val failure = runCatching {
            soldier.movement.moveToTile(game.getTile(4, 0), plannedPath = listOf(game.getTile(4, 0)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(original, json().toJson(game.gameInfo))
    }

    @Test
    fun `preview and UI use the same route and movement effects`() {
        val view = GameView(game.gameInfo, player).getMapUnitView(settler)
        val tileView = GameView(game.gameInfo, player).tileMapView.getTile(game.getTile(1, 0))
        val original = json().toJson(game.gameInfo)
        val expected = operations.moves(settler).single { it.destination == tileView.getTile() }
        assertEquals(expected, view.getCurrentTurnMove(tileView))
        assertEquals(original, json().toJson(game.gameInfo))
        assertTrue(view.tryMoveThisTurn(tileView))
        assertEquals(expected.destination, settler.currentTile)
        assertEquals(2f - expected.movementCost, settler.currentMovement, 0.001f)
        assertFalse(GameView(game.gameInfo, player, spectatorMode = true).getMapUnitView(settler)
            .tryMoveThisTurn(GameView(game.gameInfo, player).tileMapView.getTile(game.getTile(0, 0))))
    }

    @Test
    fun `movement preserves stacking transit and river costs`() {
        val soldier = game.addUnit("Warrior", player, game.getTile(0, 0))
        game.addUnit("Warrior", player, game.getTile(1, 0))
        assertTrue(operations.tryMove(soldier, game.getTile(2, 0)))
        assertEquals(game.getTile(2, 0), soldier.currentTile)
        val riverFrom = settler.currentTile
        val riverTo = riverFrom.neighbors.first()
        riverFrom.setConnectedByRiver(riverTo, true)
        val cost = MovementCost.getMovementCostBetweenAdjacentTiles(settler, riverFrom, riverTo)
        assertTrue(cost >= settler.currentMovement)
        assertTrue(operations.tryMove(settler, riverTo))
        assertEquals(0f, settler.currentMovement, 0.001f)
    }

    @Test
    fun `settlement shares picker eligibility and revalidates consumption`() {
        assertTrue(operations.canFoundCity(settler))
        assertNotNull(UnitActions.getUnitActions(settler, UnitActionType.FoundCity).single().action)
        settler.currentMovement = 0f
        assertFalse(operations.tryFoundCity(settler))
        assertNull(UnitActions.getUnitActions(settler, UnitActionType.FoundCity).single().action)
        settler.currentMovement = 1f
        assertFalse(operations.tryFoundCity(foreign))
        assertFalse(PlayerUnitOperations(player, true).tryFoundCity(settler))
        assertTrue(operations.tryFoundCity(settler))
        assertEquals(1, player.cities.size)
        assertTrue(settler.isDestroyed)
        assertFalse(operations.tryFoundCity(settler))
        val nearby = game.addUnit("Settler", player, game.getTile(1, 0))
        assertFalse(operations.canFoundCity(nearby))
        assertNull(UnitActions.getUnitActions(nearby, UnitActionType.FoundCity).single().action)
    }

    @Test
    fun `settlement requires explicit confirmation for broken promises`() {
        val city = game.addCity(other, game.getTile(5, 0))
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        other.getDiplomacyManager(player)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 20)
        city.getCenterTile().setExplored(other, true)
        assertTrue(UnitSettlement.promisesBroken(settler).isNotEmpty())
        assertFalse(operations.tryFoundCity(settler))
        assertTrue(player.cities.isEmpty())
        assertTrue(operations.tryFoundCity(settler, confirmBreakPromise = true))
    }
}
