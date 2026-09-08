package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.VictoryData
import com.unciv.logic.battle.AttackParticipantOutcome
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.attackEventsForTesting
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerCaptureOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private val game = testGame.gameInfo
    private val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val enemy = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val capital = testGame.addCity(player, testGame.getTile(-5, 0))
    private val enemyCapital = testGame.addCity(enemy, testGame.getTile(5, 0))
    private val city = testGame.addCity(enemy, testGame.getTile(1, 0), initialPopulation = 4)
    private val operations = PlayerCaptureOperations(player)

    init {
        game.currentPlayer = player.civID
        game.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(enemy)
        player.popupAlerts.clear()
        enemy.popupAlerts.clear()
    }

    private fun pending(city: City = this.city): PopupAlert {
        city.hasJustBeenConquered = true
        return PopupAlert(AlertType.CityConquered, city.id).also { player.popupAlerts.add(it) }
    }

    @Test
    fun `a human conquering without a world screen retains the city decision`() {
        assertNull(UncivGame.Current.worldScreen)
        player.getDiplomacyManager(enemy)!!.declareWar()
        val warrior = testGame.addUnit("Warrior", player, testGame.getTile(0, 0))
        city.health = 1
        Battle.attack(MapUnitCombatant(warrior), CityCombatant(city))
        assertSame(enemy, city.civ)
        assertTrue(city.hasJustBeenConquered)
        val alert = player.popupAlerts.single { it.type == AlertType.CityConquered }
        assertEquals(city.id, operations.captureDecision(alert)!!.cityId)
        assertTrue(operations.tryResolve(alert, CaptureChoice.Puppet))
        assertSame(player, city.civ)
        assertTrue(city.isPuppet)
        assertFalse(city.hasJustBeenConquered)
    }

    @Test
    fun `capture content is read only and annex resolution applies once`() {
        val alert = pending()
        val before = json().toJson(game)
        val decision = operations.captureDecision(alert)!!
        assertEquals("What would you like to do with the city of [${city.name}]?", decision.title)
        assertEquals(setOf(CaptureChoice.Annex, CaptureChoice.Puppet, CaptureChoice.Raze), decision.options.map { it.choice }.toSet())
        assertTrue(decision.options.all { it.paragraphs.isNotEmpty() })
        assertEquals(before, json().toJson(game))
        assertTrue(operations.tryResolve(alert, CaptureChoice.Annex))
        assertFalse(city.isPuppet)
        assertSame(player, city.civ)
        val after = json().toJson(game)
        assertFalse(operations.tryResolve(alert, CaptureChoice.Annex))
        assertEquals(after, json().toJson(game))
    }

    @Test
    fun `capital protection and disabled razing are validated before transfer`() {
        val alert = pending(enemyCapital)
        val option = operations.captureDecision(alert)!!.options.single { it.choice == CaptureChoice.Raze }
        assertTrue(option.unavailableReasons.single().contains("capitals"))
        assertFalse(operations.tryResolve(alert, CaptureChoice.Raze))
        assertSame(enemy, enemyCapital.civ)
        val ordinary = pending()
        game.gameParameters.noCityRazing = true
        assertTrue(operations.captureDecision(ordinary)!!.options.single { it.choice == CaptureChoice.Raze }
            .unavailableReasons.single().contains("disabled"))
        assertFalse(operations.tryResolve(ordinary, CaptureChoice.Raze))
        game.gameParameters.noCityRazing = false
        assertTrue(operations.tryResolve(ordinary, CaptureChoice.Raze))
        assertTrue(city.isBeingRazed)
        assertFalse(city.isPuppet)
        assertTrue(operations.tryManageCity(city, CaptureChoice.StopRazing))
        assertFalse(city.isBeingRazed)
        assertFalse(operations.tryManageCity(city, CaptureChoice.StopRazing))
    }

    @Test
    fun `a civilization unable to annex can puppet and raze but cannot annex later`() {
        val limited = testGame.addCiv(UniqueType.MayNotAnnexCities.text, isPlayer = true)
        testGame.addCity(limited, testGame.getTile(-5, -5))
        game.currentPlayer = limited.civID
        game.currentPlayerCiv = limited
        city.hasJustBeenConquered = true
        val alert = PopupAlert(AlertType.CityConquered, city.id)
        limited.popupAlerts.add(alert)
        val ops = PlayerCaptureOperations(limited)
        assertTrue(ops.captureDecision(alert)!!.options.single { it.choice == CaptureChoice.Annex }
            .unavailableReasons.single().contains("may not annex"))
        assertFalse(ops.tryResolve(alert, CaptureChoice.Annex))
        assertTrue(ops.tryResolve(alert, CaptureChoice.Raze))
        assertTrue(city.isPuppet)
        assertTrue(city.isBeingRazed)
        assertTrue(ops.tryManageCity(city, CaptureChoice.StopRazing))
        assertFalse(ops.tryManageCity(city, CaptureChoice.Annex))
        assertFalse(ops.tryManageCity(city, CaptureChoice.Raze))
    }

    @Test
    fun `a holy city cannot be razed even when it is not an original capital`() {
        city.religion.religionThisIsTheHolyCityOf = "Buddhism"
        val alert = pending()
        assertFalse(city.isOriginalCapital)
        assertTrue(operations.captureDecision(alert)!!.options.single { it.choice == CaptureChoice.Raze }
            .unavailableReasons.isNotEmpty())
        assertFalse(operations.tryResolve(alert, CaptureChoice.Raze))
        assertTrue(operations.tryResolve(alert, CaptureChoice.Puppet))
        assertTrue(operations.tryManageCity(city, CaptureChoice.Annex))
        assertFalse(operations.tryManageCity(city, CaptureChoice.Raze))
    }

    @Test
    fun `popup identity ownership and active player are required`() {
        val alert = pending()
        assertNull(PlayerCaptureOperations(enemy).captureDecision(alert))
        assertNull(PlayerCaptureOperations(player, spectatorMode = true).captureDecision(alert))
        assertNull(operations.captureDecision(PopupAlert(AlertType.CityConquered, city.id)))
        game.currentPlayer = enemy.civID
        game.currentPlayerCiv = enemy
        assertNotNull(operations.captureDecision(alert))
        assertFalse(operations.tryResolve(alert, CaptureChoice.Puppet))
        game.currentPlayer = player.civID
        game.currentPlayerCiv = player
        city.hasJustBeenConquered = false
        assertFalse(operations.tryResolve(alert, CaptureChoice.Puppet))
        assertSame(enemy, city.civ)
    }

    @Test
    fun `city screen operations retain puppet and capital guards`() {
        assertTrue(operations.tryResolve(pending(), CaptureChoice.Puppet))
        val view = GameView(game, player)
        assertFalse(view.getCityView(city).trySetRazing(true))
        assertTrue(view.getCityView(city).tryAnnexCity())
        assertFalse(view.getCityView(city).tryAnnexCity())
        assertTrue(view.getCityView(city).trySetRazing(true))
        assertTrue(view.getCityView(city).trySetRazing(false))
        assertFalse(view.getCityView(capital).trySetRazing(true))
        assertFalse(operations.tryManageCity(enemyCapital, CaptureChoice.Annex))
        assertFalse(PlayerCaptureOperations(player, spectatorMode = true).tryManageCity(city, CaptureChoice.Raze))
    }

    @Test
    fun `liberation returns a third party city and consumes only the decision`() {
        val original = testGame.addCiv(testGame.ruleset.nations.getValue("Egypt"))
        testGame.addCity(original, testGame.getTile(-5, -5))
        val liberated = testGame.addCity(original, testGame.getTile(3, 3))
        liberated.moveToCiv(enemy)
        val alert = pending(liberated)
        val option = operations.captureDecision(alert)!!.options.single { it.choice == CaptureChoice.Liberate }
        assertTrue(option.label.contains(original.civName))
        assertTrue(operations.tryResolve(alert, CaptureChoice.Liberate))
        assertSame(original, liberated.civ)
        assertFalse(player.popupAlerts.contains(alert))
        assertFalse(operations.tryResolve(alert, CaptureChoice.Liberate))
    }

    @Test
    fun `recaptured civilian return shares the diplomacy reward and original unit name`() {
        val barbarian = testGame.addBarbarianCiv().apply { victoryManager.civInfo = this }
        val warrior = testGame.addUnit("Warrior", player, testGame.getTile(0, -2))
        val worker = testGame.addUnit("Worker", enemy, testGame.getTile(1, -2)).apply { originalOwner = enemy.civID }
        worker.capturedBy(barbarian)
        BattleUnitCapture.captureCivilianUnit(MapUnitCombatant(warrior), MapUnitCombatant(worker))
        val alert = player.popupAlerts.single { it.type == AlertType.RecapturedCivilian }
        val decision = operations.captureDecision(alert)!!
        assertEquals(worker.id, decision.unitId)
        assertTrue(decision.title.contains(enemy.civName))
        assertTrue(decision.paragraphs.single().contains("originally belonged"))
        assertTrue(operations.tryResolve(alert, CaptureChoice.ReturnCivilian))
        assertTrue(worker.isDestroyed)
        assertEquals(1, enemy.units.getCivUnits().count { it.name == "Worker" })
        assertEquals(20f, enemy.getDiplomacyManager(player)!!.diplomaticModifiers.getValue(DiplomaticModifiers.ReturnedCapturedUnits.name), 0f)
        assertFalse(operations.tryResolve(alert, CaptureChoice.ReturnCivilian))
    }

    @Test
    fun `keeping a barbarian captured settler converts it while a vanished unit is never silently cleared`() {
        val barbarian = testGame.addBarbarianCiv().apply { victoryManager.civInfo = this }
        val warrior = testGame.addUnit("Warrior", player, testGame.getTile(0, -2))
        val settler = testGame.addUnit("Settler", enemy, testGame.getTile(1, -2)).apply { originalOwner = enemy.civID }
        settler.capturedBy(barbarian)
        BattleUnitCapture.captureCivilianUnit(MapUnitCombatant(warrior), MapUnitCombatant(settler))
        val alert = player.popupAlerts.single { it.type == AlertType.RecapturedCivilian }
        assertTrue(operations.tryResolve(alert, CaptureChoice.KeepCivilian))
        assertTrue(settler.isDestroyed)
        assertTrue(player.units.getCivUnits().any { it.name == "Worker" })
        val stale = PopupAlert(AlertType.RecapturedCivilian, testGame.getTile(7, 0).position.toPrettyString())
        player.popupAlerts.add(stale)
        assertNull(operations.captureDecision(stale))
        assertFalse(operations.tryResolve(stale, CaptureChoice.KeepCivilian))
        assertTrue(player.popupAlerts.contains(stale))
    }

    @Test
    fun `standalone movement capture persists one event with captured ownership`() {
        val warrior = testGame.addUnit("Warrior", player, testGame.getTile(0, -2))
        val worker = testGame.addUnit("Worker", enemy, testGame.getTile(1, -2))
        val outcome = BattleUnitCapture.captureCivilianUnit(MapUnitCombatant(warrior), MapUnitCombatant(worker))
        assertEquals(AttackParticipantOutcome.Captured, outcome)
        assertSame(player, worker.civ)
        val event = game.attackEventsForTesting.single()
        assertEquals(AttackParticipantOutcome.Captured, event.targets.single().outcome)
        assertEquals(event.id, game.clone().attackEventsForTesting.single().id)
        assertEquals(1, enemy.notifications.count { it.text.contains("captured") })
    }

    @Test
    fun `victory and defeat contents remain readable out of turn without mutation`() {
        game.victoryData = VictoryData(player, "Domination", 8)
        val captured = pending()
        assertTrue(operations.tryResolve(captured, CaptureChoice.Puppet))
        val wonAlert = PopupAlert(AlertType.GameHasBeenWon, "")
        val defeatedAlert = PopupAlert(AlertType.Defeated, enemy.civID)
        player.popupAlerts.add(defeatedAlert)
        enemy.popupAlerts.add(wonAlert)
        val before = json().toJson(game)
        val won = PlayerOperations(player).gameResult()!!
        val lost = PlayerOperations(enemy).gameResult()!!
        assertEquals("Victory", won.outcome)
        assertEquals("Defeat", lost.outcome)
        assertEquals(player.civID, lost.winningCivilizationId)
        assertEquals(8, won.victoryTurn)
        assertTrue(won.title.contains("You have won"))
        assertTrue(lost.title.contains(player.civName))
        assertTrue(won.paragraphs.single().isNotEmpty())
        assertTrue(lost.paragraphs.single().isNotEmpty())
        assertEquals(enemy.nation.defeated, PlayerOperations(player).informationalPopupContent(defeatedAlert)!!.paragraphs.single())
        assertTrue(PlayerOperations(enemy).informationalPopupContent(wonAlert)!!.title.contains(player.civName))
        assertNull(PlayerOperations(enemy, spectatorMode = true).gameResult())
        assertEquals(before, json().toJson(game))
        assertTrue(PlayerOperations(enemy).tryAcknowledgeAlert(wonAlert))
        assertFalse(PlayerOperations(enemy).tryAcknowledgeAlert(wonAlert))
    }

    @Test
    fun `decisive capital disposition establishes victory before the next turn`() {
        game.gameParameters.victoryTypes.add("Domination")
        assertNull(game.victoryData)
        val alert = pending(enemyCapital)
        assertTrue(operations.tryResolve(alert, CaptureChoice.Annex))
        assertEquals(player.civID, game.victoryData!!.winningCiv)
        assertEquals("Victory", PlayerOperations(player).gameResult()!!.outcome)
        assertEquals("Defeat", PlayerOperations(enemy).gameResult()!!.outcome)
        assertFalse(player.popupAlerts.contains(alert))
    }
}
