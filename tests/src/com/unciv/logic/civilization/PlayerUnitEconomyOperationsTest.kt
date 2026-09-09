package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.map.mapunit.UnitPillage
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(BaseTestRunner::class)
class PlayerUnitEconomyOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(8, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = game.addCity(player, game.getTile(0, 0))
    private val otherCity = game.addCity(other, game.getTile(6, 0))
    private val tile = game.getTile(1, 0)
    private val warrior = game.addUnit("Warrior", player, tile)
    private val foreign = game.addUnit("Warrior", other, game.getTile(5, 0))
    private val operations = PlayerUnitEconomyOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.addGold(1000)
    }

    private fun prepareUpgrade() {
        player.tech.addTechnology("Mining")
        player.tech.addTechnology("Iron Working")
        tile.tileResource = game.ruleset.tileResources.getValue("Iron")
        tile.resourceAmount = 2
        tile.setImprovement("Mine", player)
        player.cache.updateCivResources()
    }

    @Test
    fun `inspection is detached and exposes public target requirements and benefits`() {
        val before = json().toJson(game.gameInfo)
        val option = operations.upgrades(warrior).single()
        assertEquals("Legion", option.targetName)
        assertTrue(option.requiredTechnologies.contains("Iron Working"))
        assertTrue(option.strength > warrior.baseUnit.strength)
        assertFalse(option.available)
        assertTrue(option.reasons.any { it.contains("Iron Working") })
        assertEquals(mapOf("Iron" to 1), option.resourceRequirements)
        assertTrue(option.description.isNotBlank())
        operations.disband(warrior)
        operations.pillage(warrior)
        assertEquals(before, json().toJson(game.gameInfo))
        try {
            (option.reasons as MutableList).clear()
            fail("Reasons must be detached immutable data")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test
    fun `foreign spectator inactive and destroyed units reject without mutation`() {
        prepareUpgrade()
        val before = json().toJson(game.gameInfo)
        assertTrue(operations.upgrades(foreign).isEmpty())
        assertNull(operations.disband(foreign))
        assertNull(operations.pillage(foreign))
        assertNull(operations.tryUpgrade(foreign, "Spearman"))
        assertFalse(operations.tryDisband(foreign))
        assertFalse(operations.tryPillage(foreign))
        assertNull(PlayerUnitEconomyOperations(player, true).tryUpgrade(warrior, "Legion"))
        assertFalse(PlayerUnitEconomyOperations(player, true).tryDisband(warrior))
        assertNull(PlayerUnitEconomyOperations(other).tryUpgrade(foreign, "Swordsman"))
        assertFalse(PlayerUnitEconomyOperations(other).tryDisband(foreign))
        assertEquals(before, json().toJson(game.gameInfo))
        warrior.destroy()
        val after = json().toJson(game.gameInfo)
        assertNull(operations.tryUpgrade(warrior, "Legion"))
        assertFalse(operations.tryDisband(warrior))
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `paid upgrade preserves identity experience health and releases old resource use`() {
        prepareUpgrade()
        warrior.health = 64
        warrior.promotions.XP = 17
        warrior.instanceName = "Veteran"
        val id = warrior.id
        val option = operations.upgrades(warrior).single()
        assertTrue(option.reasons.toString(), option.available)
        val beforeGold = player.gold
        val beforeIron = player.getCivResourcesByName()["Iron"]
        val upgraded = operations.tryUpgrade(warrior, "Legion")!!
        assertEquals(id, upgraded.id)
        assertSame(tile, upgraded.currentTile)
        assertEquals("Legion", upgraded.name)
        assertEquals("Veteran", upgraded.instanceName)
        assertEquals(64, upgraded.health)
        assertEquals(17, upgraded.promotions.XP)
        assertEquals(0f, upgraded.currentMovement, 0f)
        assertEquals(beforeGold - option.goldCost, player.gold)
        assertEquals(beforeIron!! - 1, player.getCivResourcesByName()["Iron"])
        assertTrue(warrior.isDestroyed)
        val after = json().toJson(game.gameInfo)
        assertNull(operations.tryUpgrade(warrior, "Legion"))
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `stale upgrade action rechecks gold resources movement ownership and target`() {
        prepareUpgrade()
        val ui = UnitActionsUpgrade.getUpgradeActions(warrior).single()
        assertNotNull(ui.action)
        player.addGold(-player.gold)
        var before = json().toJson(game.gameInfo)
        ui.action!!.invoke()
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(operations.upgrades(warrior).single().reasons.contains("Not enough gold"))
        player.addGold(1000)
        tile.setPillaged()
        player.cache.updateCivResources()
        before = json().toJson(game.gameInfo)
        assertNull(operations.tryUpgrade(warrior, "Legion"))
        assertEquals(before, json().toJson(game.gameInfo))
        tile.improvementIsPillaged = false
        player.cache.updateCivResources()
        warrior.currentMovement = 0f
        before = json().toJson(game.gameInfo)
        assertNull(operations.tryUpgrade(warrior, "Legion"))
        assertFalse(operations.tryDisband(warrior))
        assertEquals(before, json().toJson(game.gameInfo))
        warrior.currentMovement = 2f
        before = json().toJson(game.gameInfo)
        assertNull(operations.tryUpgrade(warrior, "Giant Death Robot"))
        assertEquals(before, json().toJson(game.gameInfo))
        val neutral = game.addUnit("Warrior", player, game.getTile(3, 0))
        assertTrue(operations.upgrades(neutral).single().reasons.contains("The unit must be in your territory"))
        assertNull(operations.tryUpgrade(neutral, "Legion"))
    }

    @Test
    fun `upgrade cannot relocate to bypass occupied replacement slot`() {
        val baseWorker = game.createBaseUnit("Civilian").apply { upgradesTo = "Warrior" }
        val worker = game.addUnit(baseWorker.name, player, tile)
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.canUpgradeInPlace(worker, "Warrior"))
        assertNull(operations.tryUpgrade(worker, "Warrior"))
        assertSame(worker, tile.civilianUnit)
        assertSame(warrior, tile.militaryUnit)
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test
    fun `embarked upgrades and land to sea targets are not offered as legal in-place actions`() {
        prepareUpgrade()
        player.tech.addTechnology("Optics")
        game.setTileTerrain(tile.position, "Coast")
        assertTrue(warrior.isEmbarked())
        val before = json().toJson(game.gameInfo)
        assertTrue(operations.upgrades(warrior).single().reasons.contains("Embarked units cannot upgrade"))
        assertNull(operations.tryUpgrade(warrior, "Legion"))
        assertEquals(before, json().toJson(game.gameInfo))
        val baseUnit = game.createBaseUnit("Sword").apply { upgradesTo = "Trireme" }
        val custom = game.addUnit(baseUnit.name, player, game.getTile(0, 1))
        assertTrue(operations.upgrades(custom).isEmpty())
        assertNull(operations.tryUpgrade(custom, "Trireme"))
    }

    @Test
    fun `desktop ordinary upgrade invokes validated operation`() {
        prepareUpgrade()
        val option = operations.upgrades(warrior).single()
        val beforeGold = player.gold
        val ui = UnitActionsUpgrade.getUpgradeActions(warrior).single()
        assertEquals(option.title, ui.title)
        ui.action!!.invoke()
        assertEquals("Legion", tile.militaryUnit!!.name)
        assertEquals(beforeGold - option.goldCost, player.gold)
    }

    private fun raidUnit() = game.addUnit("Warrior", player, game.getTile(4, 0)).apply {
        health = 40
        currentMovement = 2f
    }

    @Test
    fun `pillage applies deterministic actual loot healing movement and tile damage once`() {
        val raider = raidUnit()
        val target = raider.currentTile
        val improvement = game.createTileImprovement("Pillaging this improvement yields approximately [+10 Gold]",
            "Pillaging this improvement yields [+3 Gold]")
        target.setImprovement(improvement.name, player)
        val before = json().toJson(game.gameInfo)
        val option = operations.pillage(raider)!!
        assertTrue(option.reasons.toString(), option.available)
        assertEquals(25, option.healing)
        assertEquals(1f, option.movementCost, 0f)
        assertEquals(UnitPillage.confirmation(improvement.name), option.confirmation)
        assertEquals(before, json().toJson(game.gameInfo))
        val random = Random(game.gameInfo.turns * target.position.hashCode().toLong())
        val expectedGold = random.nextInt(11) + random.nextInt(11) + 3
        val gold = player.gold
        assertTrue(operations.tryPillage(raider))
        assertEquals(gold + expectedGold, player.gold)
        assertEquals(65, raider.health)
        assertEquals(1f, raider.currentMovement, 0f)
        assertTrue(target.improvementIsPillaged)
        val after = json().toJson(game.gameInfo)
        assertFalse(operations.tryPillage(raider))
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `destroyed pillaged improvements are removed without damaging the road underneath`() {
        val raider = raidUnit()
        val improvement = game.createTileImprovement("Destroyed when pillaged")
        val target = raider.currentTile
        target.setImprovement(improvement.name, player)
        target.setRoadStatus(RoadStatus.Road, player)
        assertTrue(operations.tryPillage(raider))
        assertNull(target.improvement)
        assertEquals(RoadStatus.Road, target.roadStatus)
        assertFalse(target.roadIsPillaged)
        assertEquals("Road", operations.pillage(raider)!!.improvementName)
    }

    @Test
    fun `roads do not heal and free pillaging preserves movement and fortification`() {
        val raider = game.addDefaultMeleeUnitWithUniques(player, game.getTile(4, 0),
            "No movement cost to pillage", "All healing effects doubled")
        raider.health = 40
        raider.turnsFortified = 2
        raider.currentTile.setImprovement("Farm", player)
        val movement = raider.currentMovement
        assertEquals(50, operations.pillage(raider)!!.healing)
        assertTrue(operations.tryPillage(raider))
        assertEquals(90, raider.health)
        assertEquals(movement, raider.currentMovement, 0f)
        assertEquals(2, raider.turnsFortified)
        raider.currentTile.setRoadStatus(RoadStatus.Road, player)
        assertEquals(0, operations.pillage(raider)!!.healing)
        assertTrue(operations.tryPillage(raider))
        assertEquals(90, raider.health)
        assertTrue(raider.currentTile.roadIsPillaged)
    }

    @Test
    fun `pillage never declares war and rejects civilian exhausted forbidden and own tiles`() {
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        player.getDiplomacyManager(other)!!.hasOpenBorders = true
        val raider = game.addUnit("Warrior", player, game.getTile(6, 1))
        val target = raider.currentTile
        assertSame(other, target.getOwner())
        target.setImprovement("Farm", other)
        var before = json().toJson(game.gameInfo)
        assertFalse(operations.pillage(raider)!!.available)
        assertFalse(operations.tryPillage(raider))
        assertEquals(before, json().toJson(game.gameInfo))
        player.getDiplomacyManager(other)!!.declareWar()
        // Declaring war closes open borders and expels the visiting unit.
        raider.removeFromTile()
        raider.putInTile(target)
        raider.currentMovement = 2f
        assertTrue(operations.pillage(raider)!!.available)
        raider.currentMovement = 0f
        before = json().toJson(game.gameInfo)
        assertFalse(operations.tryPillage(raider))
        assertEquals(before, json().toJson(game.gameInfo))
        tile.setImprovement("Farm", player)
        assertFalse(operations.tryPillage(warrior))
        val worker = game.addUnit("Worker", player, target)
        assertFalse(operations.tryPillage(worker))
        val forbidden = game.addDefaultMeleeUnitWithUniques(player, game.getTile(4, 0), "Unable to pillage tiles")
        forbidden.currentTile.setImprovement("Farm", player)
        assertFalse(operations.tryPillage(forbidden))
    }

    @Test
    fun `disband refunds only own territory and refreshes unit upkeep`() {
        val refund = operations.disband(warrior)!!.gold
        assertTrue(refund > 0)
        assertEquals("Disband this unit for [$refund] gold?", operations.disband(warrior)!!.confirmation)
        val beforeGold = player.gold
        assertTrue(operations.tryDisband(warrior))
        assertEquals(beforeGold + refund, player.gold)
        assertNull(tile.militaryUnit)
        assertFalse(player.units.getCivUnits().any { it === warrior })
        val neutral = raidUnit()
        assertEquals(0, operations.disband(neutral)!!.gold)
        val afterGold = player.gold
        assertTrue(operations.tryDisband(neutral))
        assertEquals(afterGold, player.gold)
        assertNull(neutral.currentTile.militaryUnit)
    }

    @Test
    fun `disband passenger warning matches desktop content and passengers evacuate`() {
        val carrier = game.addDefaultMeleeUnitWithUniques(player, game.getTile(3, 0), "Can carry [2] [Air] units")
        val passenger = game.addUnit("Fighter", player, carrier.currentTile)
        passenger.isTransported = true
        val option = operations.disband(carrier)!!
        assertEquals(PlayerUnitEconomyOperations.disbandConfirmation(carrier), option.confirmation)
        assertTrue(option.confirmation.contains("Transported units"))
        assertTrue(operations.tryDisband(carrier))
        assertFalse(passenger.isDestroyed)
        assertFalse(passenger.isTransported)
        assertSame(city.getCenterTile(), passenger.currentTile)
    }
}
