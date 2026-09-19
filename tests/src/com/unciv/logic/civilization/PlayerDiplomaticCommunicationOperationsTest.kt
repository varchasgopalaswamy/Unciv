package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerDiplomaticCommunicationOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private val game = testGame.gameInfo
    private val rome = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val greece = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val operations = PlayerDiplomaticCommunicationOperations(rome)

    init {
        testGame.addCity(rome, testGame.getTile(-4, 0))
        testGame.addCity(greece, testGame.getTile(4, 0))
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.popupAlerts.clear()
        greece.popupAlerts.clear()
        activate(rome)
    }

    private fun activate(civ: Civilization) {
        game.currentPlayer = civ.civID
        game.currentPlayerCiv = civ
    }

    @Test
    fun `denouncement removes embassies and repeated or off turn actions do not mutate`() {
        rome.getDiplomacyManager(greece)!!.setModifier(DiplomaticModifiers.EstablishedEmbassy, 10f)
        greece.getDiplomacyManager(rome)!!.setModifier(DiplomaticModifiers.EstablishedEmbassy, 10f)
        val before = json().toJson(game)
        assertFalse(PlayerDiplomaticCommunicationOperations(greece).tryDenounce(rome))
        assertFalse(PlayerDiplomaticCommunicationOperations(rome, true).tryDenounce(greece))
        assertEquals(before, json().toJson(game))
        assertTrue(operations.tryDenounce(greece))
        assertEquals(30, rome.getDiplomacyManager(greece)!!.getFlag(DiplomacyFlags.Denunciation))
        assertFalse(rome.getDiplomacyManager(greece)!!.hasModifier(DiplomaticModifiers.EstablishedEmbassy))
        assertFalse(greece.getDiplomacyManager(rome)!!.hasModifier(DiplomaticModifiers.EstablishedEmbassy))
        val after = json().toJson(game)
        assertFalse(operations.tryDenounce(greece))
        assertEquals(after, json().toJson(game))
    }

    @Test
    fun `demand is queued once and only its owner can accept the offered response`() {
        assertTrue(operations.tryDemand(greece, Demand.DoNotSettleNearUs))
        assertFalse(operations.tryDemand(greece, Demand.DoNotSettleNearUs))
        assertFalse(operations.tryDemand(greece, Demand.DoNotAttackUs))
        val alert = greece.popupAlerts.single { it.type == AlertType.DemandToStopSettlingCitiesNear }
        val recipient = PlayerDiplomaticCommunicationOperations(greece)
        assertNull(operations.decision(alert))
        assertFalse(recipient.tryRespond(alert, "Accept"))
        activate(greece)
        assertFalse(recipient.tryRespond(PopupAlert(alert.type, alert.value), "Accept"))
        assertFalse(recipient.tryRespond(alert, "Forged"))
        assertTrue(recipient.tryRespond(alert, "Accept"))
        assertTrue(rome.getDiplomacyManager(greece)!!.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        assertTrue(rome.popupAlerts.any { it.type == AlertType.AcceptingDemand })
        assertFalse(recipient.tryRespond(alert, "Accept"))
    }

    @Test
    fun `military ultimatum refusal declares war once and notices only acknowledge existing effects`() {
        val alert = PopupAlert(AlertType.DemandToNotAttackUs, greece.civID)
        rome.popupAlerts.add(alert)
        assertTrue(operations.decision(alert)!!.options.single { it.name == "Refuse" }.consequences.isNotEmpty())
        assertTrue(operations.tryRespond(alert, "Refuse"))
        assertTrue(rome.isAtWarWith(greece))
        assertEquals(1, greece.popupAlerts.count { it.type == AlertType.WarDeclaration })
        assertFalse(operations.tryRespond(alert, "Refuse"))
        val reply = PopupAlert(AlertType.RejectingDemand, greece.civID)
        rome.popupAlerts.add(reply)
        assertTrue(operations.tryRespond(reply, "Protest"))
        assertEquals(1, greece.popupAlerts.count { it.type == AlertType.WarDeclaration })
    }

    @Test
    fun `native AI trade response preserves correlation and excludes observers from serialization`() {
        greece.playerType = PlayerType.AI
        rome.addGold(100)
        val request = TradeRequest(rome.civID, Trade().apply {
            theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 10, game.speed))
        }).also { it.requestId = "test-correlation" }
        var calls = 0
        request.onResponse = { status, counter ->
            assertEquals("ENACTED", status)
            assertNull(counter)
            calls++
        }
        val restored = json().fromJson(TradeRequest::class.java, json().toJson(request))
        assertEquals(request.requestId, restored.requestId)
        assertNull(restored.onResponse)
        greece.tradeRequests.add(request)
        val original = rome.gold
        TradeAutomation.respondToTradeRequests(greece, true)
        TradeAutomation.respondToTradeRequests(greece, true)
        assertEquals(1, calls)
        assertEquals(original - 10, rome.gold)
    }

    @Test
    fun `cloned games retain pending identities but never their response observers`() {
        val request = TradeRequest(rome.civID, Trade().apply {
            theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 10, game.speed))
        }).also { it.requestId = "trade"; it.onResponse = { _, _ -> error("Original observer") } }
        val alert = PopupAlert(AlertType.DeclarationOfFriendship, rome.civID).also {
            it.requestId = "friendship"
            it.onResponse = { error("Original observer") }
        }
        greece.tradeRequests.add(request)
        greece.popupAlerts.add(alert)
        val clone = greece.clone()
        assertEquals("trade", clone.tradeRequests.single().requestId)
        assertNull(clone.tradeRequests.single().onResponse)
        assertEquals("friendship", clone.popupAlerts.single().requestId)
        assertNull(clone.popupAlerts.single().onResponse)
        clone.tradeRequests.single().trade.theirOffers.single().amount = 1
        assertEquals(10, request.trade.theirOffers.single().amount)
        assertNull(json().fromJson(PopupAlert::class.java, json().toJson(alert)).onResponse)
    }

    @Test
    fun `native demand responses retain flags and do not automatically punish a promise breach`() {
        for (demand in Demand.entries) {
            val alert = PopupAlert(demand.demandAlert, greece.civID)
            rome.popupAlerts.add(alert)
            assertTrue(operations.tryRespond(alert, "Accept"))
            assertTrue(greece.getDiplomacyManager(rome)!!.hasFlag(demand.agreedToDemand))
            assertFalse(rome.isAtWarWith(greece))
        }
        val notice = PopupAlert(AlertType.CitySettledNearOtherCivDespiteOurPromise, greece.civID)
        rome.popupAlerts.add(notice)
        val opinion = greece.getDiplomacyManager(rome)!!.diplomaticModifiers.toMap()
        assertTrue(operations.tryRespond(notice, "Acknowledge"))
        assertEquals(opinion, greece.getDiplomacyManager(rome)!!.diplomaticModifiers)
    }

    @Test
    fun `an owned ultimatum cannot bypass an active native peace treaty`() {
        val peace = TradeOffer("Peace Treaty", TradeOfferType.Treaty, 1, 10)
        val treaty = Trade().apply { ourOffers.add(peace); theirOffers.add(peace.copy()) }
        rome.getDiplomacyManager(greece)!!.trades.add(treaty)
        greece.getDiplomacyManager(rome)!!.trades.add(treaty.reverse())
        val alert = PopupAlert(AlertType.DemandToNotAttackUs, greece.civID)
        rome.popupAlerts.add(alert)
        assertFalse(operations.decision(alert)!!.options.single { it.name == "Refuse" }.available)
        val before = json().toJson(game)
        assertFalse(operations.tryRespond(alert, "Refuse"))
        assertEquals(before, json().toJson(game))
        assertTrue(operations.tryRespond(alert, "Accept"))
    }

    @Test
    fun `protected minor incident responses use native influence and pledge effects`() {
        val minor = testGame.addCiv(cityStateType = "Cultured")
        testGame.addCity(minor, testGame.getTile(0, 5))
        rome.diplomacyFunctions.makeCivilizationsMeet(minor)
        minor.cityStateFunctions.addProtectorCiv(rome)
        val diplo = minor.getDiplomacyManager(rome)!!
        val influence = diplo.getInfluence()
        val first = PopupAlert(AlertType.BulliedProtectedMinor, "${greece.civID}@${minor.civID}")
        rome.popupAlerts.add(first)
        assertTrue(operations.tryRespond(first, "WithdrawProtection"))
        assertEquals(influence - 20f, diplo.getInfluence())
        assertFalse(rome.isAtWarWith(greece))
        val second = PopupAlert(AlertType.AttackedProtectedMinor, "${greece.civID}@${minor.civID}")
        rome.popupAlerts.add(second)
        assertTrue(operations.tryRespond(second, "DeclareWar"))
        assertTrue(rome.isAtWarWith(greece))
        assertEquals(influence, diplo.getInfluence())
        assertFalse(operations.tryRespond(second, "DeclareWar"))
    }
}
