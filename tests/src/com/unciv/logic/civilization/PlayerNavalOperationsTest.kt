package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerNavalOperationsTest {
    private val game = TestGame().apply {
        makeHexagonalMap(10)
        for (tile in tileMap.values) { tile.baseTerrain = "Coast"; tile.setTerrainTransients() }
    }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val home = game.getTile(0, 0).apply { baseTerrain = "Grassland"; setTerrainTransients() }
    private val away = game.getTile(6, 0).apply { baseTerrain = "Grassland"; setTerrainTransients() }
    private val city = game.addCity(player, home)
    private val otherCity = game.addCity(other, away)
    private val coast = game.getTile(1, 0)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.addGold(1000)
        for (tech in listOf("Sailing", "Optics", "Compass", "Astronomy", "Navigation")) player.tech.addTechnology(tech)
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        for (tile in game.tileMap.values) tile.setExplored(player, true)
        player.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `ranged ship attacks ships and coastal cities through native combat`() {
        player.getDiplomacyManager(other)!!.declareWar()
        val ship = game.addUnit("Frigate", player, game.getTile(4, 0))
        val enemy = game.addUnit("Trireme", other, game.getTile(5, 0))
        player.viewableTiles = game.tileMap.values.toSet()
        val operations = PlayerCombatOperations(player)
        val before = json().toJson(game.gameInfo)
        val offers = operations.attacks(ship)
        assertTrue(offers.any { it.target === away })
        val offer = offers.first { it.target === enemy.currentTile && it.attackFrom === ship.currentTile }
        assertEquals(0, offer.maxDamageToAttacker)
        assertEquals(before, json().toJson(game.gameInfo))
        val result = operations.tryAttack(ship, offer.target, offer.attackFrom)!!
        assertTrue(result.attacked)
        assertTrue(result.damageToDefender > 0)
        assertEquals(0, result.damageToAttacker)
        assertNull(operations.tryAttack(ship, offer.target, offer.attackFrom))
        UnitTurnManager(ship).startTurn()
        val cityOffer = operations.attacks(ship).first { it.target === away }
        val cityResult = operations.tryAttack(ship, away, cityOffer.attackFrom)!!
        assertTrue(cityResult.attacked && cityResult.damageToDefender > 0)
        assertSame(other, otherCity.civ) // bombardment alone does not capture
    }

    @Test
    fun `naval attack offers respect peace visibility and invisible submarines`() {
        val ship = game.addUnit("Frigate", player, coast)
        val enemy = game.addUnit("Submarine", other, game.getTile(3, 0))
        val operations = PlayerCombatOperations(player)
        assertTrue(operations.attacks(ship).isEmpty())
        player.getDiplomacyManager(other)!!.declareWar()
        player.viewableTiles = game.tileMap.values.toSet()
        assertFalse(enemy.isVisibleTo(player))
        assertFalse(operations.attacks(ship).any { it.target === enemy.currentTile })
        player.viewableInvisibleUnitsTiles = setOf(enemy.currentTile)
        assertTrue(operations.attacks(ship).any { it.target === enemy.currentTile })
        player.viewableTiles = setOf(home, coast)
        assertFalse(operations.attacks(ship).any { it.target === enemy.currentTile })
    }

    @Test
    fun `ship upgrades revalidate gold and preserve identity health and promotions`() {
        val ship = game.addUnit("Trireme", player, coast)
        ship.health = 64
        ship.promotions.XP = 17
        val operations = PlayerUnitEconomyOperations(player)
        val option = operations.upgrades(ship).single { it.targetName == "Caravel" }
        assertTrue(option.reasons.toString(), option.available)
        val ui = UnitActionsUpgrade.getUpgradeActions(ship).first().action!!
        player.addGold(-player.gold)
        val before = json().toJson(game.gameInfo)
        ui()
        assertEquals(before, json().toJson(game.gameInfo))
        assertNull(operations.tryUpgrade(ship, option.targetName))
        player.addGold(option.goldCost)
        val upgraded = operations.tryUpgrade(ship, option.targetName)!!
        assertEquals(0, player.gold)
        assertEquals(ship.id, upgraded.id)
        assertSame(coast, upgraded.currentTile)
        assertEquals(64, upgraded.health)
        assertEquals(17, upgraded.promotions.XP)
        assertEquals(0f, upgraded.currentMovement, 0f)
    }

    @Test
    fun `ship pillaging disband healing and promotions use their ordinary operations`() {
        player.getDiplomacyManager(other)!!.declareWar()
        val ship = game.addUnit("Trireme", player, game.getTile(5, 0))
        val tile = ship.currentTile
        tile.tileResource = game.ruleset.tileResources.getValue("Fish")
        tile.setImprovement("Fishing Boats", other)
        val economy = PlayerUnitEconomyOperations(player)
        assertTrue(economy.pillage(ship)!!.available)
        ship.health = 60
        assertTrue(economy.tryPillage(ship))
        assertNull(tile.improvement) // Native pillaging destroys water improvements.
        assertTrue(ship.health > 60)
        val domestic = game.addUnit("Trireme", player, coast)
        domestic.health = 60
        val orders = PlayerUnitOrders(player)
        assertTrue(orders.tryOrder(domestic, PlayerUnitOrders.Order.SLEEP_UNTIL_HEALED))
        assertTrue(orders.status(domestic)!!.healingPerTurn > 0)
        UnitTurnManager(domestic).endTurn()
        assertTrue(domestic.health > 60)
        assertTrue(orders.tryOrder(domestic, PlayerUnitOrders.Order.WAKE))
        domestic.promotions.XP = 100
        val promotion = orders.promotions(domestic)!!.choices.first { it.available }
        assertTrue(orders.tryPromote(domestic, promotion.name))
        domestic.currentMovement = domestic.getMaxMovement().toFloat()
        val refund = economy.disband(domestic)!!.gold
        val gold = player.gold
        assertTrue(economy.tryDisband(domestic))
        assertTrue(domestic.isDestroyed)
        assertEquals(gold + refund, player.gold)
    }

    @Test
    fun `work boat action consumes the builder and connects visible resources`() {
        val boat = game.addUnit("Work Boats", player, coast)
        coast.tileResource = game.ruleset.tileResources.getValue("Whales")
        player.cache.updateCivResources()
        val operations = PlayerWaterImprovementOperations(player)
        val option = operations.option(boat)!!
        assertTrue(option.available && option.consumesUnit)
        assertEquals("Fishing Boats", option.name)
        assertFalse(PlayerWaterImprovementOperations(player, true).tryCreate(boat, option.name))
        boat.currentMovement = 0f
        assertFalse(operations.tryCreate(boat, option.name))
        boat.currentMovement = 1f
        assertFalse(operations.tryCreate(boat, "Oil well"))
        assertTrue(operations.tryCreate(boat, option.name))
        assertTrue(boat.isDestroyed)
        assertEquals("Fishing Boats", coast.improvement)
        assertEquals(1, player.getCivResourcesByName()["Whales"])
        assertFalse(operations.tryCreate(boat, option.name))
    }

    @Test
    fun `work boat never advertises a hidden resource or an already improved tile`() {
        val boat = game.addUnit("Work Boats", player, coast)
        val operations = PlayerWaterImprovementOperations(player)
        coast.tileResource = game.ruleset.tileResources.getValue("Oil")
        assertNull(operations.option(boat))
        coast.tileResource = game.ruleset.tileResources.getValue("Fish")
        assertNotNull(operations.option(boat))
        coast.setImprovement("Fishing Boats", player)
        assertNull(operations.option(boat))
    }

    @Test
    fun `land embarkation and landing remain native path decisions`() {
        val settler = game.addUnit("Settler", player, home)
        val movement = PlayerUnitOperations(player)
        assertTrue(movement.tryMove(settler, coast))
        assertTrue(settler.isEmbarked())
        assertEquals(0f, settler.currentMovement, 0f)
        val landing = game.getTile(3, 0).apply { baseTerrain = "Grassland"; setTerrainTransients() }
        UnitTurnManager(settler).startTurn()
        assertNotNull(movement.route(settler, landing))
        assertTrue(movement.trySetDestination(settler, landing))
        assertSame(landing, settler.currentTile)
        assertFalse(settler.isEmbarked())
    }

    @Test
    fun `ocean restrictions distinguish coastal vessels from ocean going ships`() {
        val ocean = game.getTile(2, 0).apply { baseTerrain = "Ocean"; setTerrainTransients() }
        val coastal = game.addUnit("Trireme", player, coast)
        val movement = PlayerUnitOperations(player)
        assertNull(movement.route(coastal, ocean))
        assertFalse(movement.trySetDestination(coastal, ocean))
        coastal.destroy()
        val oceanGoing = game.addUnit("Caravel", player, coast)
        assertNotNull(movement.route(oceanGoing, ocean))
        assertTrue(movement.trySetDestination(oceanGoing, ocean))
        assertSame(ocean, oceanGoing.currentTile)
    }

    @Test
    fun `naval upgrades require actual strategic resources`() {
        val ship = game.addUnit("Frigate", player, coast)
        player.tech.addTechnology("Electronics")
        val economy = PlayerUnitEconomyOperations(player)
        val option = economy.upgrades(ship).single { it.targetName == "Battleship" }
        assertEquals(1, option.resourceRequirements["Oil"])
        assertFalse(option.available)
        assertNull(economy.tryUpgrade(ship, option.targetName))
        player.tech.addTechnology("Biology")
        player.tech.addTechnology("Refrigeration")
        coast.tileResource = game.ruleset.tileResources.getValue("Oil")
        coast.resourceAmount = 3
        coast.setImprovement("Offshore Platform", player)
        player.cache.updateCivResources()
        val available = economy.upgrades(ship).single { it.targetName == "Battleship" }
        assertTrue(available.reasons.toString(), available.available)
        assertNotNull(economy.tryUpgrade(ship, available.targetName))
        assertEquals(2, player.getCivResourcesByName()["Oil"])
    }
}
