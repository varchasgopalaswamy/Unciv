package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerPeaceOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(7) }
    private val rome = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val greece = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val romeCity = game.addCity(rome, game.getTile(0, 0))
    private val greeceCity = game.addCity(greece, game.getTile(6, 0))
    private val operations = PlayerPeaceOperations(rome)

    init {
        activate(rome)
        rome.addGold(300)
        greece.addGold(200)
    }

    private fun activate(civ: Civilization) {
        game.gameInfo.currentPlayer = civ.civID
        game.gameInfo.currentPlayerCiv = civ
    }

    private fun war(expireCooldown: Boolean = true) {
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.getDiplomacyManager(greece)!!.declareWar()
        if (expireCooldown) {
            rome.getDiplomacyManager(greece)!!.removeFlag(DiplomacyFlags.DeclaredWar)
            greece.getDiplomacyManager(rome)!!.removeFlag(DiplomacyFlags.DeclaredWar)
            rome.getDiplomacyManager(greece)!!.removeFlag(DiplomacyFlags.DeclinedPeace)
            greece.getDiplomacyManager(rome)!!.removeFlag(DiplomacyFlags.DeclinedPeace)
        }
    }

    private fun peace() = PeaceTradeTerm(Constants.peaceTreaty, TradeOfferType.Treaty, 1, game.gameInfo.speed.peaceDealDuration)
    private fun gold(amount: Int) = PeaceTradeTerm(Constants.flatGold, TradeOfferType.Gold, amount, -1)
    private fun gpt(amount: Int) = PeaceTradeTerm(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, amount, game.gameInfo.speed.dealDuration)
    private fun offer(term: PeaceTradeTerm) = TradeOffer(term.name, term.type, term.amount, term.duration)

    @Test
    fun `negotiation guards use the same declaration cooldown as the peace button`() {
        assertNull(operations.availableTerms(greece))
        assertNotNull(operations.negotiationUnavailableReason(greece))
        war(expireCooldown = false)
        assertTrue(operations.negotiationUnavailableReason(greece)!!.contains("more turns"))
        assertNull(operations.availableTerms(greece))
        assertFalse(operations.tryPropose(greece, listOf(peace()), listOf(peace())))
        // The button reads the other civilization's declaration flag.
        greece.getDiplomacyManager(rome)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        assertNull(operations.negotiationUnavailableReason(greece))
        assertNotNull(operations.availableTerms(greece))
        assertTrue(operations.tryPropose(greece, listOf(peace()), listOf(peace())))
        assertTrue(rome.isAtWarWith(greece))
        assertEquals(1, greece.tradeRequests.size)
    }

    @Test
    fun `available terms match trade UI and detached snapshots cannot alter requests`() {
        war()
        rome.stats.statsForNextTurn.gold = 7f
        greece.stats.statsForNextTurn.gold = 8f
        val actual = operations.availableTerms(greece)!!
        val expected = TradeLogic(rome, greece).ourAvailableOffers.map {
            PeaceTradeTerm(it.name, it.type, it.amount, it.duration)
        }.filter(PlayerPeaceOperations::supportsTerm)
        assertEquals(expected, actual.ourOptions)
        assertThrows(UnsupportedOperationException::class.java) { (actual.ourOptions as MutableList<*>).clear() }
        assertTrue(operations.tryPropose(greece, listOf(peace(), gold(60)), listOf(peace(), gpt(1))))
        val sent = operations.pendingOutgoing(greece)!!
        val received = PlayerPeaceOperations(greece).inspectIncoming(greece.tradeRequests.single())!!
        assertEquals(sent.ourOffers, received.theirOffers)
        assertEquals(sent.theirOffers, received.ourOffers)
        assertThrows(UnsupportedOperationException::class.java) { (received.ourOffers as MutableList<*>).clear() }
        greece.tradeRequests.single().trade.theirOffers.first { it.type == TradeOfferType.Gold }.amount = 5
        assertEquals(60, sent.ourOffers.first { it.type == TradeOfferType.Gold }.amount)
        assertEquals(60, received.theirOffers.first { it.type == TradeOfferType.Gold }.amount)
        assertNull(PlayerPeaceOperations(greece, spectatorMode = true).inspectIncoming(greece.tradeRequests.single()))
    }

    @Test
    fun `acceptance transfers only once and records symmetric timed obligations`() {
        war()
        rome.stats.statsForNextTurn.gold = 7f
        greece.stats.statsForNextTurn.gold = 8f
        assertTrue(operations.tryPropose(greece, listOf(peace(), gold(60), gpt(2)), listOf(peace(), gold(20))))
        val request = greece.tradeRequests.single()
        assertFalse(PlayerPeaceOperations(greece).tryAccept(request))
        activate(greece)
        val receiver = PlayerPeaceOperations(greece)
        assertNull(receiver.acceptUnavailableReason(request))
        assertTrue(receiver.tryAccept(request))
        assertFalse(rome.isAtWarWith(greece))
        assertEquals(260, rome.gold)
        assertEquals(240, greece.gold)
        assertTrue(greece.tradeRequests.isEmpty())
        val romanTrade = rome.getDiplomacyManager(greece)!!.trades.single()
        val greekTrade = greece.getDiplomacyManager(rome)!!.trades.single()
        assertTrue(romanTrade.reverse().equalTrade(greekTrade))
        assertEquals(game.gameInfo.speed.peaceDealDuration, rome.getDiplomacyManager(greece)!!.turnsToPeaceTreaty())
        assertEquals(game.gameInfo.speed.dealDuration, romanTrade.ourOffers.single { it.type == TradeOfferType.Gold_Per_Turn }.duration)
        val agreements = operations.activeAgreements(greece)
        assertEquals(romanTrade.ourOffers.map { it.name }, agreements.single().ourOffers.map { it.name })
        assertThrows(UnsupportedOperationException::class.java) { (agreements as MutableList<*>).clear() }
        romanTrade.ourOffers.single { it.type == TradeOfferType.Gold_Per_Turn }.duration--
        assertEquals(game.gameInfo.speed.dealDuration, agreements.single().ourOffers.single { it.type == TradeOfferType.Gold_Per_Turn }.duration)
        assertEquals(game.gameInfo.speed.dealDuration - 1, operations.activeAgreements(greece).single().ourOffers.single { it.type == TradeOfferType.Gold_Per_Turn }.duration)
        val notifications = rome.notifications.size
        assertFalse(receiver.tryAccept(request))
        assertFalse(receiver.tryReject(request))
        assertEquals(260, rome.gold)
        assertEquals(240, greece.gold)
        assertEquals(notifications, rome.notifications.size)
        assertEquals(listOf("Excellent!"), receiver.acceptancePopupContent(rome)!!.paragraphs)
        assertEquals("Farewell.", receiver.acceptancePopupContent(rome)!!.acknowledgement)
    }

    @Test
    fun `malformed terms are rejected before merging quantities or mutating either player`() {
        war()
        val valid = listOf(peace())
        val invalid = listOf(
            emptyList(), listOf(gold(10)), listOf(peace().copy(amount = 2)),
            listOf(peace(), gold(-1)), listOf(peace(), gold(0)),
            listOf(peace(), gold(301)), listOf(peace(), gold(Int.MAX_VALUE), gold(Int.MAX_VALUE)),
            listOf(peace(), gold(1).copy(duration = 30)), listOf(peace().copy(duration = 999)),
            listOf(peace(), gold(10), gold(10)),
            listOf(peace(), PeaceTradeTerm("Unobtainium", TradeOfferType.Strategic_Resource, 1, game.gameInfo.speed.dealDuration)),
            listOf(peace(), PeaceTradeTerm("Invented gold", TradeOfferType.Gold, 1, -1)),
            listOf(peace(), PeaceTradeTerm(Constants.openBorders, TradeOfferType.Agreement, 1, game.gameInfo.speed.dealDuration)),
        )
        for (terms in invalid) {
            assertFalse("Accepted $terms", operations.tryPropose(greece, terms, valid))
            assertFalse("Accepted $terms on other side", operations.tryPropose(greece, valid, terms))
            assertTrue(greece.tradeRequests.isEmpty())
            assertEquals(300, rome.gold)
            assertEquals(200, greece.gold)
        }
    }

    @Test
    fun `stale quantities and durations cannot be accepted but requests may be rejected`() {
        war()
        assertTrue(operations.tryPropose(greece, listOf(peace(), gold(100)), listOf(peace())))
        val request = greece.tradeRequests.single()
        activate(greece)
        val receiver = PlayerPeaceOperations(greece)
        rome.addGold(-201)
        assertNotNull(receiver.acceptUnavailableReason(request))
        assertFalse(receiver.tryAccept(request))
        assertEquals(1, greece.tradeRequests.size)
        rome.addGold(1)
        request.trade.ourOffers.single().duration++
        assertFalse(receiver.tryAccept(request))
        assertTrue(receiver.tryReject(request))
        assertTrue(greece.tradeRequests.isEmpty())
        assertEquals(3, rome.getDiplomacyManager(greece)!!.getFlag(DiplomacyFlags.DeclinedPeace))
        assertFalse(receiver.tryReject(request))
        assertTrue(rome.isAtWarWith(greece))
    }

    @Test
    fun `counteroffer reverses perspective and invalid drafts preserve the original request`() {
        war()
        assertTrue(operations.tryPropose(greece, listOf(peace(), gold(90)), listOf(peace())))
        val original = greece.tradeRequests.single()
        activate(greece)
        val receiver = PlayerPeaceOperations(greece)
        assertFalse(receiver.tryCounteroffer(original, listOf(peace(), gold(201)), listOf(peace())))
        assertSame(original, greece.tradeRequests.single())
        assertTrue(rome.tradeRequests.isEmpty())
        assertTrue(receiver.tryCounteroffer(original, listOf(peace(), gold(30)), listOf(peace(), gold(70))))
        assertTrue(greece.tradeRequests.isEmpty())
        val counter = rome.tradeRequests.single()
        assertEquals(greece.civID, counter.requestingCiv)
        assertEquals(70, counter.trade.ourOffers.single { it.type == TradeOfferType.Gold }.amount)
        assertEquals(30, counter.trade.theirOffers.single { it.type == TradeOfferType.Gold }.amount)
        assertFalse(rome.getDiplomacyManager(greece)!!.hasFlag(DiplomacyFlags.DeclinedPeace))
        assertFalse(receiver.tryCounteroffer(original, listOf(peace()), listOf(peace())))
        activate(rome)
        assertTrue(operations.tryAccept(counter))
        assertEquals(260, rome.gold)
        assertEquals(240, greece.gold)
    }

    @Test
    fun `unsupported requests cannot be partially accepted or countered but can be read and rejected`() {
        war()
        val trade = Trade().apply {
            ourOffers.add(offer(peace()))
            theirOffers.add(offer(peace()))
            theirOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = game.gameInfo.speed))
        }
        val request = TradeRequest(greece.civID, trade)
        rome.tradeRequests.add(request)
        assertEquals(2, operations.inspectIncoming(request)!!.theirOffers.size)
        assertTrue(operations.acceptUnavailableReason(request)!!.contains("unsupported"))
        assertFalse(operations.tryAccept(request))
        assertFalse(operations.tryCounteroffer(request, listOf(peace()), listOf(peace())))
        assertSame(request, rome.tradeRequests.single())
        assertTrue(operations.tryReject(request))
        assertTrue(rome.isAtWarWith(greece))
        assertTrue(rome.getDiplomacyManager(greece)!!.trades.isEmpty())
    }

    @Test
    fun `retraction removes only the players own offer and old decisions stay invalid`() {
        war()
        val third = game.addCiv(game.ruleset.nations.getValue("Egypt"), isPlayer = true)
        greece.diplomacyFunctions.makeCivilizationsMeet(third)
        val unrelated = TradeRequest(third.civID, Trade())
        greece.tradeRequests.add(unrelated)
        assertTrue(operations.tryPropose(greece, listOf(peace()), listOf(peace())))
        val original = greece.tradeRequests.last()
        assertFalse(operations.tryPropose(greece, listOf(peace(), gold(1)), listOf(peace())))
        assertTrue(operations.tryRetract(greece))
        assertSame(unrelated, greece.tradeRequests.single())
        assertFalse(operations.tryRetract(greece))
        assertNull(operations.pendingOutgoing(greece))
        activate(greece)
        assertFalse(PlayerPeaceOperations(greece).tryAccept(original))
        assertNull(PlayerPeaceOperations(greece).inspectIncoming(original))
    }

    @Test
    fun `active human identity spectator and foreign request guards precede mutations`() {
        war()
        val ourTerms = listOf(peace())
        assertFalse(PlayerPeaceOperations(rome, spectatorMode = true).tryPropose(greece, ourTerms, ourTerms))
        assertFalse(PlayerPeaceOperations(greece).tryPropose(rome, ourTerms, ourTerms))
        rome.playerType = PlayerType.AI
        assertFalse(operations.tryPropose(greece, ourTerms, ourTerms))
        rome.playerType = PlayerType.Human
        game.gameInfo.currentPlayerCiv = greece
        assertFalse(operations.tryPropose(greece, ourTerms, ourTerms))
        activate(rome)
        assertTrue(operations.tryPropose(greece, ourTerms, ourTerms))
        val actual = greece.tradeRequests.single()
        assertNull(operations.inspectIncoming(actual))
        assertFalse(operations.tryAccept(actual))
        activate(greece)
        val copy = TradeRequest(actual.requestingCiv, actual.trade)
        assertFalse(PlayerPeaceOperations(greece).tryAccept(copy))
        assertFalse(PlayerPeaceOperations(greece, spectatorMode = true).tryAccept(actual))
        assertTrue(rome.isAtWarWith(greece))
        assertEquals(1, greece.tradeRequests.size)
    }

    @Test
    fun `desktop trade view uses the same validation and editor creates a detached draft`() {
        war()
        val view = GameView(game.gameInfo, rome)
        val tradeView = view.civView.getTradeView(view.getForeignCivView(greece))
        tradeView.ourStagedOffers().add(offer(peace()))
        // Missing reciprocal peace must be rejected through the same route, not the unrestricted
        // trade path used for additional diplomatic features.
        assertFalse(tradeView.tryProposeStagedTrade())
        tradeView.theirStagedOffers().add(offer(peace()))
        tradeView.ourStagedOffers().add(offer(gold(301)))
        assertFalse(tradeView.tryProposeStagedTrade())
        tradeView.ourStagedOffers().single { it.type == TradeOfferType.Gold }.amount = 100
        assertTrue(tradeView.tryProposeStagedTrade())
        assertFalse(tradeView.tryProposeStagedTrade())
        assertTrue(tradeView.tryRetractOffer())
        assertTrue(tradeView.tryProposeStagedTrade())
        val request = greece.tradeRequests.single()
        activate(greece)
        val draft = PlayerPeaceOperations(greece).tryBeginCounteroffer(request)!!
        assertTrue(greece.tradeRequests.isEmpty())
        draft.theirOffers.single { it.type == TradeOfferType.Gold }.amount = 3
        assertEquals(100, request.trade.theirOffers.single { it.type == TradeOfferType.Gold }.amount)
        assertNull(PlayerPeaceOperations(greece).tryBeginCounteroffer(request))
        assertTrue(rome.tradeRequests.isEmpty())
    }

    @Test
    fun `acceptance clears reciprocal peace offers but preserves unrelated requests`() {
        war()
        assertTrue(operations.tryPropose(greece, listOf(peace()), listOf(peace())))
        activate(greece)
        val receiver = PlayerPeaceOperations(greece)
        assertTrue(receiver.tryPropose(rome, listOf(peace(), gold(20)), listOf(peace())))
        val obsolete = rome.tradeRequests.single()
        val unrelated = TradeRequest(rome.civID, Trade().apply { theirOffers.add(offer(gold(5))) })
        greece.tradeRequests.add(unrelated)
        assertTrue(receiver.tryAccept(greece.tradeRequests.first()))
        assertSame(unrelated, greece.tradeRequests.single())
        assertTrue(rome.tradeRequests.isEmpty())
        activate(rome)
        assertFalse(operations.tryAccept(obsolete))
        assertEquals(300, rome.gold)
        assertEquals(200, greece.gold)
    }

    @Test
    fun `peace resource terms use actual tradeable resources and accepted obligations`() {
        war()
        romeCity.getCenterTile().setTileResource("Silk")
        greeceCity.getCenterTile().setTileResource("Iron")
        greeceCity.getCenterTile().resourceAmount = 4
        // Resources under a city still require the technology that permits their extraction.
        rome.tech.addTechnology("Calendar")
        greece.tech.addTechnology("Mining")
        greece.tech.addTechnology("Iron Working")
        rome.cache.updateCivResources()
        greece.cache.updateCivResources()
        val options = operations.availableTerms(greece)!!
        val silk = options.ourOptions.single { it.name == "Silk" }
        val iron = options.theirOptions.single { it.name == "Iron" }
        assertEquals(1, silk.amount)
        assertEquals(4, iron.amount)
        assertFalse(operations.tryPropose(greece, listOf(peace(), silk.copy(amount = 2)), listOf(peace())))
        assertTrue(operations.tryPropose(greece, listOf(peace(), silk), listOf(peace(), iron.copy(amount = 2))))
        activate(greece)
        assertTrue(PlayerPeaceOperations(greece).tryAccept(greece.tradeRequests.single()))
        assertEquals(0, rome.getResourceAmount("Silk"))
        assertEquals(1, greece.getResourceAmount("Silk"))
        assertEquals(2, rome.getResourceAmount("Iron"))
        assertEquals(2, greece.getResourceAmount("Iron"))
        val duration = rome.getDiplomacyManager(greece)!!.trades.single().ourOffers.single { it.name == "Silk" }.duration
        assertEquals(game.gameInfo.speed.dealDuration, duration)
    }

    @Test
    fun `accepted exports and lost income cannot be oversold in peace terms`() {
        war()
        romeCity.getCenterTile().setTileResource("Silk")
        rome.tech.addTechnology("Calendar")
        rome.cache.updateCivResources()
        val silk = operations.availableTerms(greece)!!.ourOptions.single { it.name == "Silk" }
        val third = game.addCiv(game.ruleset.nations.getValue("Egypt"), isPlayer = true)
        rome.diplomacyFunctions.makeCivilizationsMeet(third)
        rome.getDiplomacyManager(third)!!.trades.add(Trade().apply { ourOffers.add(offer(silk)) })
        rome.cache.updateCivResources()
        assertEquals(0, rome.getResourceAmount("Silk"))
        assertEquals(0, operations.availableTerms(greece)!!.ourOptions.single { it.name == "Silk" }.amount)
        assertFalse(operations.tryPropose(greece, listOf(peace(), silk), listOf(peace())))
        rome.stats.statsForNextTurn.gold = 5f
        assertTrue(operations.tryPropose(greece, listOf(peace(), gpt(5)), listOf(peace())))
        val request = greece.tradeRequests.single()
        activate(greece)
        rome.stats.statsForNextTurn.gold = 4.9f
        assertFalse(PlayerPeaceOperations(greece).tryAccept(request))
        assertSame(request, greece.tradeRequests.single())
    }
}
