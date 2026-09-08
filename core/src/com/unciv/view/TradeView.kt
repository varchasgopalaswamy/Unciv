package com.unciv.view

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PeaceTradeTerm
import com.unciv.logic.civilization.PlayerPeaceOperations
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import yairm210.purity.annotations.Readonly

/** View of a trade negotiation between [civ] (always the viewer's own civ) and [otherCiv].
 *  Always built fresh, same as [TradeLogic] itself - staged offers live in this instance only. */
class TradeView(private val civ: Civilization, private val otherCiv: Civilization, gameView: GameView) : GameBasedView<Civilization>(civ, civ, gameView = gameView) {
    private val tradeLogic = TradeLogic(civ, otherCiv)
    private val peaceOperations = PlayerPeaceOperations(civ, gameView.spectatorMode)

    // Data retrieval - lists are the live mutable instances, callers may add/remove offers directly
    @Readonly fun ourAvailableOffers(): TradeOffersList = tradeLogic.ourAvailableOffers
    @Readonly fun theirAvailableOffers(): TradeOffersList = tradeLogic.theirAvailableOffers
    @Readonly fun ourStagedOffers(): TradeOffersList = tradeLogic.currentTrade.ourOffers
    @Readonly fun theirStagedOffers(): TradeOffersList = tradeLogic.currentTrade.theirOffers
    @Readonly fun hasPendingOfferFromUs(): Boolean =
        otherCiv.tradeRequests.any { it.requestingCiv == civ.civID }

    // Actions - staging
    fun setStagedTrade(trade: Trade) = tradeLogic.currentTrade.set(trade)

    /** Restores a trade we already sent to [otherCiv] (if any) into staging, so it can be re-displayed or retracted. */
    fun tryLoadOurPendingOffer(): Boolean {
        val existing = otherCiv.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return false
        tradeLogic.currentTrade.set(existing.trade.reverse())
        return true
    }

    // Actions - proposing
    fun tryProposeStagedTrade(): Boolean {
        if (PlayerPeaceOperations.usesSupportedPeaceTerms(tradeLogic.currentTrade)) {
            fun terms(offers: TradeOffersList) = offers.map { PeaceTradeTerm(it.name, it.type, it.amount, it.duration) }
            return peaceOperations.tryPropose(otherCiv, terms(tradeLogic.currentTrade.ourOffers), terms(tradeLogic.currentTrade.theirOffers))
        }
        if (tradeLogic.currentTrade.ourOffers.isEmpty() && tradeLogic.currentTrade.theirOffers.isEmpty()) return false
        otherCiv.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
        civ.cache.updateCivResources()
        return true
    }

    fun tryRetractOffer(): Boolean {
        val existing = otherCiv.tradeRequests.firstOrNull { it.requestingCiv == civ.civID }
        if (existing != null && PlayerPeaceOperations.usesSupportedPeaceTerms(existing.trade))
            return peaceOperations.tryRetract(otherCiv)
        if (!hasPendingOfferFromUs()) return false
        otherCiv.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        return true
    }
}
