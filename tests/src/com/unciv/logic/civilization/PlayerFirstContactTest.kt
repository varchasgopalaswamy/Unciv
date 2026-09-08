package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerFirstContactTest {
    private val game = TestGame().apply { makeHexagonalMap(8) }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val operations = PlayerOperations(player)

    init {
        game.addCity(player, game.getTile(-4, 0))
        game.addCity(other, game.getTile(4, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
    }

    private fun meet(civilization: Civilization = other): PopupAlert {
        assertFalse(player.knows(civilization))
        player.diplomacyFunctions.makeCivilizationsMeet(civilization)
        return player.popupAlerts.single { it.type == AlertType.FirstContact && it.value == civilization.civID }
    }

    private fun state() = json().toJson(game.gameInfo)

    @Test
    fun `real meeting exposes the popup text and reading is detached and read only`() {
        val alert = meet()
        val before = state()
        val introduction = operations.firstContactIntroduction(alert)!!
        assertEquals(other.civID, introduction.civilizationId)
        assertEquals("Greece", introduction.civilizationName)
        assertEquals(other.getLeaderDisplayName(), introduction.leaderName)
        assertEquals(other.nation.introduction, introduction.message)
        assertEquals("A pleasure to meet you.", introduction.acknowledgement)
        assertEquals(introduction, operations.firstContactIntroduction(alert))
        assertEquals(before, state())

        val originalMessage = other.nation.introduction
        other.nation.introduction = "A later greeting"
        assertEquals(originalMessage, introduction.message)
        assertEquals("A later greeting", operations.firstContactIntroduction(alert)!!.message)
    }

    @Test
    fun `acknowledgement removes only the owned alert without repeating meeting effects`() {
        val alert = meet()
        val foreignAlert = other.popupAlerts.single { it.type == AlertType.FirstContact && it.value == player.civID }
        val diplomacy = player.getDiplomacyManager(other)
        player.popupAlerts.remove(alert)
        val expected = state()
        player.popupAlerts.add(alert)

        assertTrue(operations.tryAcknowledgeAlert(alert))
        assertEquals(expected, state())
        assertSame(diplomacy, player.getDiplomacyManager(other))
        assertTrue(other.popupAlerts.any { it === foreignAlert })
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertNull(operations.firstContactIntroduction(alert))
        assertEquals(expected, state())
    }

    @Test
    fun `city state greeting uses its own text and acknowledgement cannot repeat the gift`() {
        val cityState = game.addCiv(game.ruleset.nations.getValue("Milan"))
        val goldBeforeMeeting = player.gold
        val alert = meet(cityState)
        assertTrue(player.gold > goldBeforeMeeting)
        val goldAfterMeeting = player.gold
        val before = state()
        val introduction = operations.firstContactIntroduction(alert)!!
        assertEquals(cityState.civID, introduction.civilizationId)
        assertEquals("Milan", introduction.civilizationName)
        assertEquals(cityState.getLeaderDisplayName(), introduction.leaderName)
        assertEquals("We have encountered the City-State of [Milan]!", introduction.message)
        assertEquals("Excellent!", introduction.acknowledgement)
        assertEquals(before, state())

        player.popupAlerts.remove(alert)
        val expected = state()
        player.popupAlerts.add(alert)
        assertTrue(operations.tryAcknowledgeAlert(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertEquals(goldAfterMeeting, player.gold)
        assertEquals(expected, state())
    }

    @Test
    fun `foreign fabricated and removed alerts cannot be read or acknowledged`() {
        val alert = meet()
        val foreign = other.popupAlerts.single { it.type == AlertType.FirstContact && it.value == player.civID }
        val fabricated = PopupAlert(alert.type, alert.value)
        val before = state()
        for (unowned in listOf(foreign, fabricated)) {
            assertNull(operations.firstContactIntroduction(unowned))
            assertFalse(operations.tryAcknowledgeAlert(unowned))
        }
        assertEquals(before, state())
        player.popupAlerts.remove(alert)
        val removedState = state()
        assertNull(operations.firstContactIntroduction(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertEquals(removedState, state())
    }

    @Test
    fun `out of turn reading is permitted but acknowledgement requires an active human player`() {
        val alert = meet()
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        val before = state()
        assertNotNull(operations.firstContactIntroduction(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertNull(PlayerOperations(player, spectatorMode = true).firstContactIntroduction(alert))
        assertFalse(PlayerOperations(player, spectatorMode = true).tryAcknowledgeAlert(alert))
        assertEquals(before, state())

        game.gameInfo.currentPlayer = player.civID
        assertFalse(operations.tryAcknowledgeAlert(alert))
        game.gameInfo.currentPlayerCiv = player
        player.playerType = PlayerType.AI
        assertFalse(operations.tryAcknowledgeAlert(alert))
        player.playerType = PlayerType.Human
        assertTrue(operations.tryAcknowledgeAlert(alert))
    }

    @Test
    fun `missing or unmet civilizations cannot manufacture an introduction`() {
        val missing = PopupAlert(AlertType.FirstContact, "No such civilization")
        val unmet = PopupAlert(AlertType.FirstContact, other.civID)
        player.popupAlerts.addAll(listOf(missing, unmet))
        val before = state()
        for (alert in listOf(missing, unmet)) {
            assertNull(operations.firstContactIntroduction(alert))
            assertFalse(operations.tryAcknowledgeAlert(alert))
        }
        assertEquals(before, state())
        assertFalse(player.knows(other))
    }

    @Test
    fun `removed civilizations cannot keep an introduction live`() {
        val alert = meet()
        game.gameInfo.civilizations.remove(other)
        assertNull(operations.firstContactIntroduction(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        game.gameInfo.civilizations.add(other)
        game.gameInfo.civilizations.remove(player)
        assertNull(operations.firstContactIntroduction(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertTrue(player.popupAlerts.any { it === alert })
    }

    @Test
    fun `first contact support does not allow acknowledgement of consequential choices`() {
        val alerts = AlertType.entries.filter { it !in PlayerOperations.informationalAlerts }
            .map { PopupAlert(it, other.civID) }
        player.popupAlerts.addAll(alerts)
        val before = state()
        for (alert in alerts) {
            assertNull(operations.firstContactIntroduction(alert))
            assertFalse(operations.tryAcknowledgeAlert(alert))
        }
        assertEquals(before, state())
    }
}
