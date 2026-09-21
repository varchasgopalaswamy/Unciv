package com.unciv.logic.map

import com.unciv.logic.map.mapunit.FoundingUnitRejection
import com.unciv.logic.map.mapunit.UnitSettlement
import com.unciv.logic.map.tile.SettlementRejection
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
        for (tile in game.gameInfo.tileMap.values) tile.setContinent(0)
    }

    @Test
    fun `site blockers explain the native same and different continent distances`() {
        val nearby = game.getTile(3, 0)
        val blocker = nearby.settlementBlockers(civilization).single()
        assertEquals(SettlementRejection.CITY_TOO_CLOSE, blocker.reason)
        assertSame(capital, blocker.city)
        assertEquals(3, blocker.distance)
        assertEquals(4, blocker.minimumDistance)
        assertFalse(nearby.canBeSettled(civilization))
        assertTrue(game.getTile(4, 0).canBeSettled(civilization))

        nearby.clearContinent()
        nearby.setContinent(1)
        assertTrue(nearby.canBeSettled(civilization))
        val acrossWater = game.getTile(2, 0)
        acrossWater.clearContinent()
        acrossWater.setContinent(1)
        val otherContinent = acrossWater.settlementBlockers(civilization).single()
        assertEquals(2, otherContinent.distance)
        assertEquals(3, otherContinent.minimumDistance)
        assertFalse(acrossWater.canBeSettled(civilization))
    }

    @Test
    fun `movement recovers independently of blocked city sites`() {
        val settler = game.addUnit("Settler", civilization, game.getTile(3, 0))
        settler.currentMovement = 0f
        assertTrue(UnitSettlement.hasFoundingAbility(settler))
        assertEquals(listOf(FoundingUnitRejection.NO_MOVEMENT), UnitSettlement.unitBlockers(settler))
        assertTrue(game.getTile(4, 0).settlementBlockers(civilization).none())
        assertFalse(UnitSettlement.canFoundCity(settler, game.getTile(4, 0)))
        settler.currentMovement = 2f
        assertTrue(UnitSettlement.unitBlockers(settler).isEmpty())
        assertFalse(UnitSettlement.canFoundCity(settler))
        assertTrue(UnitSettlement.canFoundCity(settler, game.getTile(4, 0)))
    }

    @Test
    fun `own territory and adjacent settlers allow founding but foreign territory does not`() {
        val site = game.getTile(4, 0)
        val settler = game.addUnit("Settler", civilization, site)
        capital.expansion.takeOwnership(site)
        val neighbor = game.addCiv(game.ruleset.nations.getValue("Rome"))
        game.addUnit("Settler", neighbor, game.getTile(5, 0))
        assertTrue(UnitSettlement.canFoundCity(settler))
        assertTrue(site.settlementBlockers(civilization).none())

        val foreignCity = game.addCity(neighbor, game.getTile(-6, 0))
        foreignCity.expansion.takeOwnership(site)
        val blocker = site.settlementBlockers(civilization).single()
        assertEquals(SettlementRejection.FOREIGN_TERRITORY, blocker.reason)
        assertSame(foreignCity, blocker.city)
        assertFalse(UnitSettlement.canFoundCity(settler, site))
    }

    @Test
    fun `terrain and unit ability blockers match founding availability`() {
        val settler = game.addUnit("Settler", civilization, game.getTile(4, 0))
        val water = game.setTileTerrain(HexCoord(4, 1), "Ocean")
        val mountain = game.setTileTerrain(HexCoord(5, 1), "Mountain")
        assertTrue(water.settlementBlockers(civilization).any { it.reason == SettlementRejection.WATER })
        assertTrue(mountain.settlementBlockers(civilization).any { it.reason == SettlementRejection.IMPASSABLE })
        assertFalse(UnitSettlement.canFoundCity(settler, water))
        assertFalse(UnitSettlement.canFoundCity(settler, mountain))
        val warrior = game.addUnit("Warrior", civilization, game.getTile(4, 0))
        assertFalse(UnitSettlement.hasFoundingAbility(warrior))
        assertEquals(listOf(FoundingUnitRejection.FOUNDING_ABILITY_UNAVAILABLE), UnitSettlement.unitBlockers(warrior))
        assertFalse(UnitSettlement.canFoundCity(warrior))
        settler.destroy()
        assertEquals(listOf(FoundingUnitRejection.DESTROYED), UnitSettlement.unitBlockers(settler))
        assertFalse(UnitSettlement.canFoundCity(settler))
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
