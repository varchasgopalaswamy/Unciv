package com.unciv.logic.map

import com.unciv.logic.map.mapunit.UnitSettlement
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class UnitSettlementTest {
    private val game = TestGame().apply { makeHexagonalMap(8, "Grassland") }
    private val civilization = game.addCiv(game.ruleset.nations.getValue("Egypt"))
    private val capital = game.addCity(civilization, game.getTile(0, 0))

    init {
        game.gameInfo.currentPlayer = civilization.civID
        game.gameInfo.currentPlayerCiv = civilization
    }

    @Test
    fun `prospective sites can be checked while the settler is still in the capital`() {
        val settler = game.addUnit("Settler", civilization, capital.getCenterTile())
        assertFalse(UnitSettlement.canFoundCity(settler))
        assertTrue(UnitSettlement.canFoundCity(settler, game.getTile(4, 0)))
        assertFalse(UnitSettlement.canFoundCity(settler, game.getTile(1, 0)))
        val water = game.setTileTerrain(HexCoord(4, 1), "Ocean")
        assertFalse(UnitSettlement.canFoundCity(settler, water))
        assertNull(UnitSettlement.tryFoundCity(settler))
        assertEquals(capital.getCenterTile(), settler.currentTile)
        assertEquals(1, civilization.cities.size)
        assertFalse(settler.isDestroyed)
        settler.currentMovement = 0f
        assertFalse(UnitSettlement.canFoundCity(settler, game.getTile(4, 0)))
    }

    @Test
    fun `retained founding action cannot settle at a different location`() {
        val site = game.getTile(4, 0)
        val settler = game.addUnit("Settler", civilization, site)
        val action = UnitActions.getUnitActions(settler, UnitActionType.FoundCity).single().action!!
        settler.movement.moveToTile(game.getTile(5, 0))
        action()
        assertEquals(1, civilization.cities.size)
        assertFalse(settler.isDestroyed)

        settler.currentMovement = 2f
        settler.movement.moveToTile(site)
        action()
        assertEquals(2, civilization.cities.size)
        assertTrue(civilization.cities.any { it.location == site.position })
        assertTrue(settler.isDestroyed)
    }

    @Test
    fun `retained founding action rechecks settlement distance`() {
        val settler = game.addUnit("Settler", civilization, game.getTile(4, 0))
        val action = UnitActions.getUnitActions(settler, UnitActionType.FoundCity).single().action!!
        val neighbor = game.addCiv(game.ruleset.nations.getValue("Rome"))
        game.addCity(neighbor, game.getTile(6, 0))
        action()
        assertEquals(1, civilization.cities.size)
        assertFalse(settler.isDestroyed)
    }

    @Test
    fun `retained founding action rechecks movement and consumes the settler once`() {
        val settler = game.addUnit("Settler", civilization, game.getTile(4, 0))
        val action = UnitActions.getUnitActions(settler, UnitActionType.FoundCity).single().action!!
        settler.currentMovement = 0f
        action()
        assertEquals(1, civilization.cities.size)
        assertFalse(settler.isDestroyed)
        settler.currentMovement = 1f
        action()
        action()
        assertEquals(2, civilization.cities.size)
        assertTrue(settler.isDestroyed)
    }
}
