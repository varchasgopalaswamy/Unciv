package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.ModOptions
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerDiplomacyOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(8) }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val operations = PlayerDiplomacyOperations(player)
    private val originalWarMessage = player.nation.declaringWar
    private val originalAttackedMessage = other.nation.attacked

    @After
    fun restoreNationText() {
        // Ruleset test clones share nation objects with the cache.
        player.nation.declaringWar = originalWarMessage
        other.nation.attacked = originalAttackedMessage
    }

    init {
        game.addCity(player, game.getTile(-4, 0))
        game.addCity(other, game.getTile(4, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
    }

    private fun meet() = player.diplomacyFunctions.makeCivilizationsMeet(other)
    private fun state() = json().toJson(game.gameInfo)

    @Test
    fun `unmet self fabricated removed and city state targets are not war options`() {
        val beforeMeeting = state()
        assertNull(operations.options(other))
        assertNull(operations.declarationConfirmation(other))
        assertFalse(operations.tryDeclareWar(other))
        assertEquals(beforeMeeting, state())
        meet()
        val fabricated = Civilization(other.nation).apply { gameInfo = game.gameInfo }
        val cityState = game.addCiv(game.ruleset.nations.getValue("Milan"))
        player.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val before = state()
        for (invalid in listOf(player, fabricated, cityState)) {
            assertNull(operations.options(invalid))
            assertFalse(operations.tryDeclareWar(invalid))
        }
        assertEquals(before, state())
        game.gameInfo.civilizations.remove(other)
        assertNull(operations.options(other))
        assertFalse(operations.tryDeclareWar(other))
        game.gameInfo.civilizations.add(other)
        game.gameInfo.civilizations.remove(player)
        assertNull(operations.options(other))
        assertFalse(operations.tryDeclareWar(other))
    }

    @Test
    fun `options are detached immutable and reading does not declare war`() {
        meet()
        val before = state()
        val options = operations.options(other)!!
        assertEquals(DiplomaticStatus.Peace, options.status)
        assertEquals(0, options.peaceTreatyTurns)
        assertTrue(options.declareWar.available)
        assertTrue(options.declareWar.unavailableReasons.isEmpty())
        assertEquals(listOf("Declare war on [Greece]?"), options.declarationConfirmation)
        assertEquals(before, state())
        assertTrue(operations.tryDeclareWar(other))
        assertEquals(DiplomaticStatus.Peace, options.status)
        assertTrue(options.declareWar.available)
        for (list in listOf(options.declarationConfirmation, options.declareWar.unavailableReasons)) {
            try {
                (list as MutableList<String>).add("Changed through the query")
                fail("Returned choices must be immutable")
            } catch (_: UnsupportedOperationException) { }
        }
    }

    @Test
    fun `defending leader response shares desktop text without revealing an unmet leader`() {
        assertNull(operations.declarationResponse(other))
        meet()
        val before = state()
        val response = operations.declarationResponse(other)!!
        assertEquals(other.getLeaderDisplayName(), response.title)
        assertEquals(listOf(other.nation.attacked).filter(String::isNotEmpty), response.paragraphs)
        assertEquals("Very well.", response.acknowledgement)
        assertEquals(before, state())
        other.nation.attacked = "You will regret this attack."
        assertEquals(originalAttackedMessage, response.paragraphs.single())
        assertEquals(listOf("You will regret this attack."), operations.declarationResponse(other)!!.paragraphs)
        other.nation.attacked = ""
        assertTrue(operations.declarationResponse(other)!!.paragraphs.isEmpty())
        try {
            (response.paragraphs as MutableList<String>).add("Changed through the query")
            fail("The response paragraphs must be immutable")
        } catch (_: UnsupportedOperationException) { }
        assertNull(PlayerDiplomacyOperations(player, spectatorMode = true).declarationResponse(other))
    }

    @Test
    fun `validated declaration applies bilateral war effects and cannot repeat them`() {
        meet()
        player.tradeRequests.add(TradeRequest(other.civID, Trade()))
        other.tradeRequests.add(TradeRequest(player.civID, Trade()))
        assertTrue(operations.tryDeclareWar(other))
        for ((a, b) in listOf(player to other, other to player)) {
            val manager = a.getDiplomacyManager(b)!!
            assertEquals(DiplomaticStatus.War, manager.diplomaticStatus)
            assertEquals(game.ruleset.modOptions.constants.minimumWarDuration, manager.getFlag(DiplomacyFlags.DeclaredWar))
            assertTrue(a.tradeRequests.isEmpty())
        }
        assertEquals(1, other.popupAlerts.count { it.type == AlertType.WarDeclaration })
        val options = operations.options(other)!!
        assertFalse(options.declareWar.available)
        assertTrue("Already at war." in options.declareWar.unavailableReasons)
        val after = state()
        assertFalse(operations.tryDeclareWar(other))
        assertEquals(after, state())
    }

    @Test
    fun `out of turn AI spectator and inconsistent current player cannot declare war`() {
        meet()
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        val before = state()
        assertNotNull(operations.options(other))
        assertFalse(operations.options(other)!!.declareWar.available)
        assertTrue("Not the active human player." in operations.options(other)!!.declareWar.unavailableReasons)
        assertFalse(operations.tryDeclareWar(other))
        assertNull(PlayerDiplomacyOperations(player, spectatorMode = true).options(other))
        assertFalse(PlayerDiplomacyOperations(player, spectatorMode = true).tryDeclareWar(other))
        assertEquals(before, state())

        game.gameInfo.currentPlayer = player.civID
        assertFalse(operations.tryDeclareWar(other))
        game.gameInfo.currentPlayerCiv = player
        player.playerType = PlayerType.AI
        assertFalse(operations.tryDeclareWar(other))
        player.playerType = PlayerType.Human
        assertTrue(operations.tryDeclareWar(other))
    }

    @Test
    fun `defeated participants and fixed diplomatic relationships disable war`() {
        meet()
        val otherCities = other.cities
        other.cities = emptyList()
        assertTrue(other.isDefeated())
        assertTrue("Target civilization has been defeated." in operations.options(other)!!.declareWar.unavailableReasons)
        assertFalse(operations.tryDeclareWar(other))
        other.cities = otherCities
        val playerCities = player.cities
        player.cities = emptyList()
        assertTrue("Your civilization has been defeated." in operations.options(other)!!.declareWar.unavailableReasons)
        assertFalse(operations.tryDeclareWar(other))
        player.cities = playerCities
        game.ruleset.modOptions = ModOptions().apply {
            uniques.add(UniqueType.DiplomaticRelationshipsCannotChange.text)
        }
        val before = state()
        assertTrue("Diplomatic relationships cannot change." in operations.options(other)!!.declareWar.unavailableReasons)
        assertFalse(operations.tryDeclareWar(other))
        assertEquals(before, state())
    }

    @Test
    fun `peace treaty uses the same remaining duration as the desktop and blocks declarations`() {
        meet()
        val manager = player.getDiplomacyManager(other)!!
        val trade = Trade().apply {
            ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, duration = 7))
        }
        manager.trades.add(trade)
        val before = state()
        val options = operations.options(other)!!
        assertEquals(manager.turnsToPeaceTreaty(), options.peaceTreatyTurns)
        assertEquals(7, options.peaceTreatyTurns)
        assertTrue("Peace treaty active for [7] turns." in options.declareWar.unavailableReasons)
        assertFalse(operations.tryDeclareWar(other))
        assertEquals(before, state())
        trade.ourOffers.single().duration = 0
        assertEquals(7, options.peaceTreatyTurns)
        assertEquals(0, operations.options(other)!!.peaceTreatyTurns)
        assertTrue(operations.tryDeclareWar(other))
    }

    @Test
    fun `confirmation shares promise and pact warnings without naming unknown partners`() {
        meet()
        val unknownPartner = game.addCiv(game.ruleset.nations.getValue("Egypt"))
        val knownPartner = game.addCiv(game.ruleset.nations.getValue("India"))
        val ourPartner = game.addCiv(game.ruleset.nations.getValue("Persia"))
        other.diplomacyFunctions.makeCivilizationsMeet(unknownPartner)
        other.diplomacyFunctions.makeCivilizationsMeet(knownPartner)
        player.diplomacyFunctions.makeCivilizationsMeet(knownPartner)
        player.diplomacyFunctions.makeCivilizationsMeet(ourPartner)
        other.getDiplomacyManager(player)!!.setFlag(DiplomacyFlags.AgreedToNotAttackUs, 10)
        other.getDiplomacyManager(unknownPartner)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        other.getDiplomacyManager(knownPartner)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        player.getDiplomacyManager(ourPartner)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        val before = state()
        val warnings = operations.declarationConfirmation(other)!!
        assertEquals(warnings, operations.options(other)!!.declarationConfirmation)
        assertTrue(warnings.contains("This will break your promise to not attack them. Other leaders will view this unfavorably."))
        assertTrue(warnings.contains("[An unknown civilization] will also join them in the war"))
        assertTrue(warnings.contains("[India] will also join them in the war"))
        assertTrue(warnings.contains("This will cancel your defensive pact with [Persia]"))
        assertFalse(warnings.joinToString().contains("Egypt"))
        assertEquals(before, state())
        assertFalse(player.knows(unknownPartner))
    }

    @Test
    fun `war declaration popup carries full text and both equivalent responses without repeating war`() {
        player.nation.declaringWar = "Our armies are coming. Prepare to defend your people!"
        player.civID = "Rome-player"
        game.gameInfo.currentPlayer = player.civID
        meet()
        assertTrue(operations.tryDeclareWar(other))
        val alert = other.popupAlerts.single { it.type == AlertType.WarDeclaration }
        assertEquals(player.civID, alert.value)
        val recipientOperations = PlayerOperations(other)
        val before = state()
        val content = recipientOperations.informationalPopupContent(alert)!!
        assertEquals(player.getLeaderDisplayName(), content.title)
        assertEquals(listOf("DECLARATION OF WAR", player.nation.declaringWar), content.paragraphs)
        assertEquals("Very well.", content.acknowledgement)
        assertEquals(listOf("You'll pay for this!"), content.additionalAcknowledgements)
        assertFalse(recipientOperations.tryAcknowledgeAlert(alert))
        assertEquals(before, state())
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        other.popupAlerts.remove(alert)
        val acknowledgedState = state()
        other.popupAlerts.add(alert)
        assertTrue(recipientOperations.tryAcknowledgeAlert(alert))
        assertEquals(acknowledgedState, state())
        assertFalse(recipientOperations.tryAcknowledgeAlert(alert))
        assertTrue(player.isAtWarWith(other))
    }

    @Test
    fun `war messages with missing unknown defeated or unowned sources cannot be read`() {
        val recipient = PlayerOperations(other)
        val unknown = PopupAlert(AlertType.WarDeclaration, player.civID).also { other.popupAlerts.add(it) }
        assertNull(recipient.informationalPopupContent(unknown))
        meet()
        val content = recipient.informationalPopupContent(unknown)!!
        val originalMessage = player.nation.declaringWar
        player.nation.declaringWar = ""
        assertEquals(listOf("DECLARATION OF WAR"), recipient.informationalPopupContent(unknown)!!.paragraphs)
        assertEquals(originalMessage, content.paragraphs.last())
        assertNull(recipient.informationalPopupContent(PopupAlert(unknown.type, unknown.value)))
        val missing = PopupAlert(AlertType.WarDeclaration, "Missing player").also { other.popupAlerts.add(it) }
        assertNull(recipient.informationalPopupContent(missing))
        player.cities = emptyList()
        assertNull(recipient.informationalPopupContent(unknown))
        assertFalse(recipient.tryAcknowledgeAlert(unknown))
    }
}
