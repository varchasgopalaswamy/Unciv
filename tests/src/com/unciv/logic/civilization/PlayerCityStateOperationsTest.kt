package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager.nextTurn
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerCityStateOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(12, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val rival = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val minor = game.addCiv(cityStateType = "Cultured")
    private val city = game.addCity(player, game.getTile(-6, 0))
    private val rivalCity = game.addCity(rival, game.getTile(0, -6))
    private val minorCity = game.addCity(minor, game.getTile(4, 0))
    private val operations = PlayerCityStateOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(minor)
        rival.diplomacyFunctions.makeCivilizationsMeet(minor)
        player.diplomacyFunctions.makeCivilizationsMeet(rival)
        player.addGold(3000)
        player.popupAlerts.clear()
    }

    private fun option(name: String) = operations.options(minor).single { it.name == name }
    private fun serialized() = json().toJson(game.gameInfo)

    @Test fun `options are pure and detached and require a met city state`() {
        val before = serialized()
        val options = operations.options(minor)
        repeat(3) { assertEquals(options, operations.options(minor)) }
        assertEquals(before, serialized())
        assertTrue(operations.options(rival).isEmpty())
        assertTrue(PlayerCityStateOperations(player, true).options(minor).isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (options as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (options.first().reasons as MutableList).clear() }
        val unknown = game.addCiv(cityStateType = "Maritime")
        assertTrue(operations.options(unknown).isEmpty())
        assertFalse(operations.tryAct(unknown, "giftGold250"))
    }

    @Test fun `Gold gifts use native amounts and shift the alliance`() {
        val expected = minor.cityStateFunctions.influenceGainedByGift(player, 1000)
        val gold = player.gold
        val influence = minor.getDiplomacyManager(player)!!.getInfluence()
        minor.getDiplomacyManager(rival)!!.setInfluence(60f)
        assertEquals(rival, minor.allyCiv)
        assertTrue(operations.tryAct(minor, "giftGold1000"))
        assertEquals(gold - 1000, player.gold)
        assertEquals(influence + expected, minor.getDiplomacyManager(player)!!.getInfluence(), 0f)
        assertEquals(player, minor.allyCiv)
        assertTrue(rival.notifications.any { it.text.contains("lost our alliance") })
        val before = serialized()
        assertFalse(operations.tryAct(minor, "giftGold1"))
        player.addGold(-player.gold)
        val broke = serialized()
        assertFalse(option("giftGold250").available)
        assertFalse(operations.tryAct(minor, "giftGold250"))
        assertEquals(broke, serialized())
        assertNotEquals(before, broke)
    }

    @Test fun `protection obeys native cooldowns resting point and withdrawal cost`() {
        assertTrue(operations.tryAct(minor, "pledgeProtection"))
        assertTrue(minor.cityStateFunctions.getProtectorCivs().contains(player))
        assertEquals(10f, operations.influence(minor)!!.restingPoint, 0f)
        val before = serialized()
        assertFalse(operations.tryAct(minor, "revokeProtection"))
        assertEquals(before, serialized())
        val manager = minor.getDiplomacyManager(player)!!
        repeat(10) { manager.nextTurn() }
        val influence = manager.getInfluence()
        assertTrue(operations.tryAct(minor, "revokeProtection"))
        assertEquals(influence - 20f, manager.getInfluence(), 0f)
        assertFalse(option("pledgeProtection").available)
        assertEquals(20, manager.getFlag(DiplomacyFlags.RecentlyWithdrewProtection))
    }

    @Test fun `war and peace preserve cooldowns treaties and allied wars`() {
        assertTrue(operations.tryAct(minor, "declareWar"))
        assertTrue(player.isAtWarWith(minor))
        assertFalse(option("giftGold250").available)
        assertFalse(operations.tryAct(minor, "makePeace"))
        player.getDiplomacyManager(minor)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        assertTrue(operations.tryAct(minor, "makePeace"))
        assertFalse(player.isAtWarWith(minor))
        assertTrue(player.getDiplomacyManager(minor)!!.turnsToPeaceTreaty() > 0)
        assertFalse(operations.tryAct(minor, "declareWar"))
        player.getDiplomacyManager(minor)!!.trades.clear()
        minor.getDiplomacyManager(player)!!.trades.clear()
        minor.getDiplomacyManager(rival)!!.setInfluence(70f)
        player.getDiplomacyManager(rival)!!.declareWar()
        assertTrue(player.isAtWarWith(minor))
        player.getDiplomacyManager(minor)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        assertFalse(option("makePeace").available)
    }

    @Test fun `tribute willingness is rechecked and native consequences apply`() {
        assertFalse(option("goldTribute").available)
        val before = serialized()
        assertFalse(operations.tryAct(minor, "goldTribute"))
        assertEquals(before, serialized())
        minorCity.population.setPopulation(5)
        for (tile in minorCity.getCenterTile().getTilesAtDistance(2)) game.addUnit("Giant Death Robot", player, tile)
        assertTrue(option("goldTribute").reasons.toString(), option("goldTribute").available)
        val gold = player.gold
        val amount = minor.cityStateFunctions.goldGainedByTribute()
        val influence = minor.getDiplomacyManager(player)!!.getInfluence()
        assertTrue(operations.tryAct(minor, "goldTribute"))
        assertEquals(gold + amount, player.gold)
        assertEquals(influence - 15, minor.getDiplomacyManager(player)!!.getInfluence(), 0f)
        assertFalse(operations.tryAct(minor, "workerTribute"))
        minor.removeFlag(CivFlags.RecentlyBullied.name)
        val units = player.units.getCivUnits().count()
        assertTrue(option("workerTribute").reasons.toString(), operations.tryAct(minor, "workerTribute"))
        assertEquals(units + 1, player.units.getCivUnits().count())
        assertEquals(-60f, minor.getDiplomacyManager(player)!!.getInfluence(), 0f)
    }

    @Test fun `resource improvements spend Gold once and update native resource supply`() {
        player.tech.addTechnology("Mining", false)
        minor.tech.addTechnology("Mining", false)
        val tile = minorCity.getTiles().first { !it.isCityCenter() }
        tile.setTileResource("Gems")
        tile.setImprovement(null)
        minor.getDiplomacyManager(player)!!.setInfluence(70f)
        val option = operations.options(minor).single { it.position == tile.position && it.improvement == "Mine" }
        assertTrue(option.available)
        player.addGold(199 - player.gold)
        val unaffordable = serialized()
        assertFalse(operations.tryAct(minor, option.name))
        assertEquals(unaffordable, serialized())
        player.addGold(1)
        val gold = player.gold
        assertTrue(operations.tryAct(minor, option.name))
        assertEquals("Mine", tile.improvement)
        assertEquals(gold - 200, player.gold)
        assertFalse(operations.tryAct(minor, option.name))
        assertTrue(minor.cityStateFunctions.getCityStateResourcesForAlly().any { it.resource.name == "Gems" && it.amount > 0 })
    }

    @Test fun `diplomatic marriage requires native unique alliance cooldown and current funds`() {
        assertTrue(operations.options(minor).none { it.kind == PlayerCityStateOperations.Kind.DiplomaticMarriage })
        val austria = game.addCiv(game.ruleset.nations.getValue("Austria"), isPlayer = true)
        game.addCity(austria, game.getTile(0, 6))
        austria.diplomacyFunctions.makeCivilizationsMeet(minor)
        austria.addGold(3000)
        game.gameInfo.currentPlayer = austria.civID
        game.gameInfo.currentPlayerCiv = austria
        val austrianOperations = PlayerCityStateOperations(austria)
        assertFalse(austrianOperations.tryAct(minor, "diplomaticMarriage"))
        minor.getDiplomacyManager(austria)!!.setInfluence(70f)
        assertFalse(austrianOperations.tryAct(minor, "diplomaticMarriage"))
        val diplomacy = austria.getDiplomacyManager(minor)!!
        repeat(diplomacy.getFlag(DiplomacyFlags.MarriageCooldown)) { diplomacy.nextTurn() }
        val cost = minor.cityStateFunctions.getDiplomaticMarriageCost()
        austria.addGold(cost - 1 - austria.gold)
        assertFalse(austrianOperations.tryAct(minor, "diplomaticMarriage"))
        austria.addGold(1)
        assertTrue(austrianOperations.tryAct(minor, "diplomaticMarriage"))
        assertEquals(0, austria.gold)
        assertEquals(austria, minorCity.civ)
        assertTrue(minorCity.isPuppet)
        assertTrue(austria.popupAlerts.any { it.type == AlertType.DiplomaticMarriage && it.value == minorCity.id })
        assertFalse(austrianOperations.tryAct(minor, "diplomaticMarriage"))
    }

    @Test fun `desktop unit gift uses shared ownership movement and turn checks`() {
        val tile = minorCity.getTiles().first { !it.isCityCenter() }
        val unit = game.addUnit("Warrior", player, tile)
        val influence = minor.getDiplomacyManager(player)!!.getInfluence()
        val button = UnitActions.getUnitActions(unit, UnitActionType.GiftUnit).single()
        unit.currentMovement = 0f
        val before = serialized()
        button.action!!.invoke()
        assertEquals(before, serialized())
        unit.currentMovement = 2f
        game.gameInfo.currentPlayer = rival.civID
        game.gameInfo.currentPlayerCiv = rival
        val inactive = serialized()
        button.action!!.invoke()
        assertEquals(inactive, serialized())
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        assertTrue(operations.tryGiftUnit(unit))
        assertEquals(minor, unit.civ)
        assertEquals(influence + 5, minor.getDiplomacyManager(player)!!.getInfluence(), 0f)
        assertFalse(operations.tryGiftUnit(unit))
        val worker = game.addUnit("Worker", player, minorCity.getTiles().first { !it.isCityCenter() && it !== tile })
        assertNull(operations.giftOption(worker))
    }
}
