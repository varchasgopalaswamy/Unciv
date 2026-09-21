package com.unciv.logic.automation.unit

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class SettlerAutomationTest {
    @Test
    fun `settler can leave an existing capital and found a second city`() {
        val game = TestGame().apply { makeHexagonalMap(8, "Grassland") }
        val civilization = game.addCiv(game.ruleset.nations.getValue("Egypt"))
        val capital = game.addCity(civilization, game.getTile(0, 0))
        val settler = game.addUnit("Settler", civilization, capital.getCenterTile())
        game.gameInfo.currentPlayer = civilization.civID
        game.gameInfo.currentPlayerCiv = civilization
        game.gameInfo.turns = 19
        for (tile in game.tileMap.values) tile.setExplored(civilization, true)

        SpecificUnitAutomation.automateSettlerActions(settler, hashSetOf())
        assertNotEquals(capital.getCenterTile(), settler.currentTile)
        repeat(10) {
            if (!settler.isDestroyed) {
                game.gameInfo.turns++
                settler.currentMovement = settler.getMaxMovement().toFloat()
                SpecificUnitAutomation.automateSettlerActions(settler, hashSetOf())
            }
        }

        assertEquals(2, civilization.cities.size)
        assertTrue(settler.isDestroyed)
        assertEquals(1, civilization.cities.count { it.location == capital.location })
    }
}
