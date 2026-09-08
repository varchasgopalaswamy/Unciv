package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerInformationalPopupTest {
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

    private fun alert(type: AlertType, value: String = "") = PopupAlert(type, value).also {
        player.popupAlerts.add(it)
    }

    private fun state() = json().toJson(game.gameInfo)

    @Test
    fun `opening introduction contains the desktop caption and both paragraphs in order`() {
        val alert = alert(AlertType.StartIntro)
        val before = state()
        val content = operations.informationalPopupContent(alert)!!
        assertEquals(player.getLeaderDisplayName(), content.title)
        assertEquals(listOf(player.nation.startIntroPart1, player.nation.startIntroPart2), content.paragraphs)
        assertEquals("Let's begin!", content.acknowledgement)
        assertNull(content.quote)
        assertEquals(before, state())

        val firstParagraph = player.nation.startIntroPart1
        player.nation.startIntroPart1 = "Changed introduction"
        assertEquals(firstParagraph, content.paragraphs.first())
        assertEquals("Changed introduction", operations.informationalPopupContent(alert)!!.paragraphs.first())
        try {
            (content.paragraphs as MutableList<String>)[0] = "Changed through the query"
            fail("The returned paragraph list must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test
    fun `researched technology includes its quote and the civilization specific desktop description`() {
        val technology = game.ruleset.technologies.getValue("Iron Working")
        val alert = alert(AlertType.TechResearched, technology.name)
        val before = state()
        val content = operations.informationalPopupContent(alert)!!
        assertEquals(technology.name, content.title)
        assertEquals(technology.quote, content.quote)
        assertEquals(listOf(technology.getDescription(player)), content.paragraphs)
        assertEquals(Constants.close, content.acknowledgement)
        assertTrue(content.paragraphs.single().contains("Legion"))
        assertFalse(content.paragraphs.single().contains("Swordsman"))
        assertFalse(content.paragraphs.single() == technology.getDescription(other))
        assertEquals(before, state())

        other.popupAlerts.add(PopupAlert(AlertType.TechResearched, technology.name))
        val otherContent = PlayerOperations(other).informationalPopupContent(other.popupAlerts.last())!!
        assertTrue(otherContent.paragraphs.single().contains("Swordsman"))
        assertFalse(otherContent.paragraphs.single().contains("Legion"))
        assertEquals(listOf(technology.getDescription(other)), otherContent.paragraphs)
    }

    @Test
    fun `completed wonder includes the desktop short description and optional quote`() {
        val wonder = game.ruleset.buildings.getValue("Stonehenge")
        assertTrue(wonder.isWonder)
        val alert = alert(AlertType.WonderBuilt, wonder.name)
        val before = state()
        val content = operations.informationalPopupContent(alert)!!
        assertEquals(wonder.name, content.title)
        assertEquals(wonder.quote, content.quote)
        assertEquals(listOf(wonder.getShortDescription()), content.paragraphs)
        assertEquals(Constants.close, content.acknowledgement)
        assertEquals(before, state())

        val originalQuote = wonder.quote
        wonder.quote = ""
        assertNull(operations.informationalPopupContent(alert)!!.quote)
        assertEquals(originalQuote, content.quote)
    }

    @Test
    fun `golden age content and acknowledgement do not trigger the event again`() {
        player.goldenAges.enterGoldenAge()
        val alert = player.popupAlerts.single { it.type == AlertType.GoldenAge }
        val before = state()
        val content = operations.informationalPopupContent(alert)!!
        assertEquals("GOLDEN AGE", content.title)
        assertEquals(listOf("Your citizens have been happy with your rule for so long that the empire enters a Golden Age!"), content.paragraphs)
        assertEquals(Constants.close, content.acknowledgement)
        assertNull(content.quote)
        assertEquals(before, state())

        player.popupAlerts.remove(alert)
        val expected = state()
        player.popupAlerts.add(alert)
        assertTrue(operations.tryAcknowledgeAlert(alert))
        assertEquals(expected, state())
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertEquals(expected, state())
    }

    @Test
    fun `first contact content preserves the shared introduction and caption`() {
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        val alert = player.popupAlerts.single { it.type == AlertType.FirstContact }
        val before = state()
        val introduction = operations.firstContactIntroduction(alert)!!
        val content = operations.informationalPopupContent(alert)!!
        assertEquals(introduction.leaderName, content.title)
        assertEquals(listOf(introduction.message), content.paragraphs)
        assertEquals(introduction.acknowledgement, content.acknowledgement)
        assertNull(content.quote)
        assertEquals(before, state())
    }

    @Test
    fun `foreign fabricated removed and missing target alerts cannot be read or acknowledged`() {
        val owned = alert(AlertType.TechResearched, "Agriculture")
        val fabricated = PopupAlert(owned.type, owned.value)
        val foreign = PopupAlert(owned.type, owned.value).also { other.popupAlerts.add(it) }
        val missingTech = alert(AlertType.TechResearched, "Missing technology")
        val missingWonder = alert(AlertType.WonderBuilt, "Missing wonder")
        val notAWonder = alert(AlertType.WonderBuilt, "Monument")
        val before = state()
        for (invalid in listOf(fabricated, foreign, missingTech, missingWonder, notAWonder)) {
            assertNull(operations.informationalPopupContent(invalid))
            assertFalse(operations.tryAcknowledgeAlert(invalid))
        }
        assertEquals(before, state())

        assertNotNull(operations.informationalPopupContent(owned))
        assertTrue(operations.tryAcknowledgeAlert(owned))
        val afterAcknowledgement = state()
        assertNull(operations.informationalPopupContent(owned))
        assertFalse(operations.tryAcknowledgeAlert(owned))
        assertEquals(afterAcknowledgement, state())
        assertTrue(other.popupAlerts.any { it === foreign })
    }

    @Test
    fun `content stays readable out of turn while acknowledgement requires an active human player`() {
        val alert = alert(AlertType.StartIntro)
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        val before = state()
        assertNotNull(operations.informationalPopupContent(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        assertNull(PlayerOperations(player, spectatorMode = true).informationalPopupContent(alert))
        assertFalse(PlayerOperations(player, spectatorMode = true).tryAcknowledgeAlert(alert))
        assertEquals(before, state())

        game.gameInfo.currentPlayer = player.civID
        assertFalse(operations.tryAcknowledgeAlert(alert))
        game.gameInfo.currentPlayerCiv = player
        player.playerType = PlayerType.AI
        assertFalse(operations.tryAcknowledgeAlert(alert))
        player.playerType = PlayerType.Human
        game.gameInfo.civilizations.remove(player)
        assertNull(operations.informationalPopupContent(alert))
        assertFalse(operations.tryAcknowledgeAlert(alert))
        game.gameInfo.civilizations.add(player)
        assertTrue(operations.tryAcknowledgeAlert(alert))
    }

    @Test
    fun `consequential alerts cannot be converted into informational acknowledgements`() {
        val alerts = AlertType.entries.filter { it !in PlayerOperations.informationalAlerts }
            .map { alert(it, other.civID) }
        val before = state()
        for (alert in alerts) {
            assertNull(operations.informationalPopupContent(alert))
            assertFalse(operations.tryAcknowledgeAlert(alert))
        }
        assertEquals(before, state())
    }
}
