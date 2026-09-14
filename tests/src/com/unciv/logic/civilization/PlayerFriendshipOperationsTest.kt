package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerFriendshipOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private val game = testGame.gameInfo
    private val rome = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val greece = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val operations = PlayerFriendshipOperations(greece)

    init {
        testGame.addCity(rome, testGame.getTile(-4, 0))
        testGame.addCity(greece, testGame.getTile(4, 0))
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        game.currentPlayer = rome.civID
        game.currentPlayerCiv = rome
    }

    private fun request() = PopupAlert(AlertType.DeclarationOfFriendship, rome.civID).also { greece.popupAlerts.add(it) }
    private fun activate() {
        game.currentPlayer = greece.civID
        game.currentPlayerCiv = greece
    }

    @Test
    fun `popup content reads out of turn but only its owner on turn may accept`() {
        val alert = request()
        val before = json().toJson(game)
        val decision = operations.decision(alert)!!
        assertTrue(decision.content.paragraphs.last().isNotBlank())
        assertNull(decision.unavailableReason)
        assertFalse(operations.tryRespond(alert, true))
        assertNull(PlayerFriendshipOperations(rome).decision(alert))
        assertNull(PlayerFriendshipOperations(greece, spectatorMode = true).decision(alert))
        assertEquals(before, json().toJson(game))
        activate()
        assertFalse(operations.tryRespond(PopupAlert(alert.type, alert.value), true))
        assertTrue(operations.tryRespond(alert, true))
        assertEquals(PlayerFriendshipOperations.DURATION, rome.getDiplomacyManager(greece)!!.getFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertEquals(PlayerFriendshipOperations.DURATION, greece.getDiplomacyManager(rome)!!.getFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertFalse(operations.tryRespond(alert, true))
    }

    @Test
    fun `intervening war disables acceptance but allows reading and declining a stale request`() {
        val alert = request()
        rome.getDiplomacyManager(greece)!!.declareWar()
        activate()
        assertNotNull(operations.decision(alert)!!.unavailableReason)
        assertFalse(operations.tryRespond(alert, true))
        assertTrue(operations.tryRespond(alert, false))
        assertFalse(greece.getDiplomacyManager(rome)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertEquals(20, rome.getDiplomacyManager(greece)!!.getFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship))
    }

    @Test
    fun `self and either direction of denunciation prevent friendship`() {
        assertNotNull(operations.stateReason(greece))
        rome.getDiplomacyManager(greece)!!.setFlag(DiplomacyFlags.Denunciation, 30)
        assertNotNull(operations.stateReason(rome))
        assertNotNull(PlayerFriendshipOperations(rome).stateReason(greece))
    }
}
