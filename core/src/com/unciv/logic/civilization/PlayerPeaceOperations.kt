package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.unique.UniqueType
import java.util.Collections

/** A detached term. Amount is the available quantity when used in [PeaceTradeOptions]. */
data class PeaceTradeTerm(val name: String, val type: TradeOfferType, val amount: Int, val duration: Int) {
    internal fun toOffer() = TradeOffer(name, type, amount, duration)
}

data class PeaceTradeOptions(val ourOptions: List<PeaceTradeTerm>, val theirOptions: List<PeaceTradeTerm>)

/** Always from the inspecting player's perspective: ourOffers are what that player gives. */
data class PeaceTrade(val ourOffers: List<PeaceTradeTerm>, val theirOffers: List<PeaceTradeTerm>)

/** Validated human peace negotiations using the same offers, request direction and effects as trade UI.
 *
 * This operation set covers mutual peace, gold, gold per turn and tradable luxury/strategic resources.
 * It deliberately refuses to accept or replace requests containing other terms. Rejection remains
 * possible for any owned request. Pending requests are identified by object identity, never by terms.
 * All mutations recheck authority and current quantities while holding the game monitor. Callers
 * must additionally respect their UI input state. The monitor does not protect arbitrary engine writes.
 */
class PlayerPeaceOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    private fun knownPartner(other: Civilization): Boolean = !spectatorMode && !civ.isSpectator() &&
        civ.isMajorCiv() && other !== civ && other.gameInfo === civ.gameInfo &&
        other.isMajorCiv() && civ.gameInfo.civilizations.any { it === civ } &&
        civ.gameInfo.civilizations.any { it === other } && civ.knows(other) && other.knows(civ)

    private fun counterpart(request: TradeRequest): Civilization? {
        if (civ.tradeRequests.none { it === request }) return null
        return civ.gameInfo.civilizations.firstOrNull { it.civID == request.requestingCiv && knownPartner(it) }
    }

    /** Read-only eligibility independent of whose turn it is. This does not authorize a mutation:
     * [tryPropose] and the other request operations still require the active human player.
     */
    fun negotiationStateReason(other: Civilization): String? = synchronized(civ.gameInfo) {
        when {
            !knownPartner(other) -> "A known major civilization is required."
            civ.isDefeated() || other.isDefeated() -> "A civilization has been defeated."
            civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange) ->
                "Diplomatic relationships cannot change in this game."
            !civ.isAtWarWith(other) -> "These civilizations are already at peace."
            other.getDiplomacyManager(civ)!!.hasFlag(DiplomacyFlags.DeclaredWar) ->
                "Peace negotiations are unavailable for [${other.getDiplomacyManager(civ)!!.getFlag(DiplomacyFlags.DeclaredWar)}] more turns."
            else -> null
        }
    }

    fun negotiationUnavailableReason(other: Civilization): String? = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) "Only the active human player can negotiate peace."
        else negotiationStateReason(other)
    }

    /** Like opening the trade table: quantities are visible even when its action button is disabled.
     * An incoming peace offer also opens that table, without the negotiation-button cooldown.
     */
    fun availableTerms(other: Civilization): PeaceTradeOptions? = synchronized(civ.gameInfo) {
        if (!knownPartner(other) || !civ.isAtWarWith(other) || civ.isDefeated() || other.isDefeated())
            return@synchronized null
        val hasIncomingPeace = civ.tradeRequests.any {
            it.requestingCiv == other.civID && it.trade.isPeaceTreaty()
        }
        if (negotiationStateReason(other) != null && !hasIncomingPeace) return@synchronized null
        val logic = TradeLogic(civ, other)
        fun available(offers: List<TradeOffer>, owner: Civilization): List<PeaceTradeTerm> {
            val currentResources = owner.getCivResourcesByName()
            return immutable(offers.map(::term).filter(::supportsTerm).map { term ->
                // The table's tradable-origin total can still include copies already exported.
                // Offer only quantities which pass the same current-supply guard as acceptance.
                if (term.type == TradeOfferType.Luxury_Resource || term.type == TradeOfferType.Strategic_Resource)
                    term.copy(amount = minOf(term.amount, currentResources[term.name] ?: 0).coerceAtLeast(0))
                else term
            })
        }
        PeaceTradeOptions(
            available(logic.ourAvailableOffers, civ),
            available(logic.theirAvailableOffers, other),
        )
    }

    /** The trade acknowledgement is presentation only; closing it has no additional game effect. */
    fun acceptancePopupContent(other: Civilization): InformationalPopupContent? = synchronized(civ.gameInfo) {
        if (!knownPartner(other)) null else InformationalPopupContent(
            title = other.getLeaderDisplayName(),
            paragraphs = immutable(listOf("Excellent!")),
            acknowledgement = "Farewell.",
        )
    }

    fun inspectIncoming(request: TradeRequest): PeaceTrade? = synchronized(civ.gameInfo) {
        if (counterpart(request) == null) null else snapshot(request.trade)
    }

    /** Only this player's offer is observable, never another player's requests to the partner. */
    fun pendingOutgoing(other: Civilization): PeaceTrade? = synchronized(civ.gameInfo) {
        if (!knownPartner(other)) return@synchronized null
        val request = other.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return@synchronized null
        snapshot(request.trade.reverse())
    }

    /** The current-trades overview retains all clauses of each still-active agreement, including
     * immediate consideration and expired clauses while longer obligations continue. Durations
     * distinguish those rows from obligations which still have turns remaining.
     */
    fun activeAgreements(other: Civilization): List<PeaceTrade> = synchronized(civ.gameInfo) {
        if (!knownPartner(other)) emptyList()
        else immutable(civ.getDiplomacyManager(other)!!.trades.map(::snapshot))
    }

    fun acceptUnavailableReason(request: TradeRequest): String? = synchronized(civ.gameInfo) {
        val other = counterpart(request) ?: return@synchronized "This trade request is no longer available."
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized "Only the active human player can respond."
        if (civ.isDefeated() || other.isDefeated()) return@synchronized "A civilization has been defeated."
        if (!isSupportedTrade(request.trade)) return@synchronized "This request contains unsupported peace terms."
        if (!civ.isAtWarWith(other)) return@synchronized "These civilizations are already at peace."
        if (civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange))
            return@synchronized "Diplomatic relationships cannot change in this game."
        if (!validTerms(other, snapshot(request.trade))) "The offered quantities or durations are no longer available."
        else null
    }

    fun rejectUnavailableReason(request: TradeRequest): String? = synchronized(civ.gameInfo) {
        when {
            counterpart(request) == null -> "This trade request is no longer available."
            !PlayerOperations(civ, spectatorMode).canAct() -> "Only the active human player can respond."
            else -> null
        }
    }

    fun counterofferUnavailableReason(request: TradeRequest): String? = synchronized(civ.gameInfo) {
        val other = counterpart(request) ?: return@synchronized "This trade request is no longer available."
        when {
            !isSupportedTrade(request.trade) -> "This request contains unsupported peace terms."
            other.tradeRequests.any { it.requestingCiv == civ.civID } -> "Retract your existing offer first."
            else -> negotiationUnavailableReason(other)
        }
    }

    fun retractUnavailableReason(other: Civilization): String? = synchronized(civ.gameInfo) {
        when {
            !knownPartner(other) -> "A known major civilization is required."
            !PlayerOperations(civ, spectatorMode).canAct() -> "Only the active human player can retract an offer."
            other.tradeRequests.none { it.requestingCiv == civ.civID } -> "There is no pending offer to retract."
            else -> null
        }
    }

    fun tryPropose(other: Civilization, ourTerms: List<PeaceTradeTerm>, theirTerms: List<PeaceTradeTerm>): Boolean =
        synchronized(civ.gameInfo) {
            if (negotiationUnavailableReason(other) != null ||
                other.tradeRequests.any { it.requestingCiv == civ.civID }) return@synchronized false
            val trade = PeaceTrade(ourTerms.toList(), theirTerms.toList())
            if (!validTerms(other, trade)) return@synchronized false
            send(other, trade)
            true
        }

    fun tryAccept(request: TradeRequest): Boolean = synchronized(civ.gameInfo) {
        if (acceptUnavailableReason(request) != null) return@synchronized false
        val other = counterpart(request) ?: return@synchronized false
        val logic = TradeLogic(civ, other)
        logic.currentTrade.set(toTrade(snapshot(request.trade)))
        // Remove the decision before applying effects; repeated calls cannot transfer gold twice.
        civ.tradeRequests.remove(request)
        logic.acceptTrade()
        // Both directions may contain offers sent on successive turns. Once peace is signed those
        // alternative peace offers are obsolete; leave unrelated trade requests untouched.
        civ.tradeRequests.removeAll { it.requestingCiv == other.civID && it.trade.isPeaceTreaty() }
        other.tradeRequests.removeAll { it.requestingCiv == civ.civID && it.trade.isPeaceTreaty() }
        other.addNotification("[${civ.civName}] has accepted your trade request", NotificationCategory.Trade,
            civ.civName, NotificationIcon.Trade)
        true
    }

    fun tryReject(request: TradeRequest): Boolean = synchronized(civ.gameInfo) {
        if (rejectUnavailableReason(request) != null) return@synchronized false
        val other = counterpart(request) ?: return@synchronized false
        request.decline(civ)
        civ.tradeRequests.remove(request)
        other.cache.updateCivResources()
        other.addNotification("[${civ.civName}] has denied your trade request", NotificationCategory.Trade,
            civ.civName, NotificationIcon.Trade)
        true
    }

    /** Completes the popup's counteroffer path in one operation. An invalid draft keeps the incoming
     * request intact; a successful replacement does not also decline it or repeat its effects.
     */
    fun tryCounteroffer(request: TradeRequest, ourTerms: List<PeaceTradeTerm>, theirTerms: List<PeaceTradeTerm>): Boolean =
        synchronized(civ.gameInfo) {
            if (counterofferUnavailableReason(request) != null) return@synchronized false
            val other = counterpart(request) ?: return@synchronized false
            val trade = PeaceTrade(ourTerms.toList(), theirTerms.toList())
            if (!validTerms(other, trade)) return@synchronized false
            civ.tradeRequests.remove(request)
            send(other, trade)
            other.cache.updateCivResources()
            true
        }

    /** Opens the desktop editor. Its draft is detached and the request is consumed just as closing
     * the trade popup would consume it. Cancelling that editor does not send a counteroffer.
     */
    fun tryBeginCounteroffer(request: TradeRequest): Trade? = synchronized(civ.gameInfo) {
        if (counterofferUnavailableReason(request) != null) return@synchronized null
        val other = counterpart(request) ?: return@synchronized null
        val draft = toTrade(snapshot(request.trade))
        civ.tradeRequests.remove(request)
        other.cache.updateCivResources()
        draft
    }

    fun tryRetract(other: Civilization): Boolean = synchronized(civ.gameInfo) {
        if (retractUnavailableReason(other) != null) return@synchronized false
        other.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        true
    }

    private fun send(other: Civilization, trade: PeaceTrade) {
        other.tradeRequests.add(TradeRequest(civ.civID, toTrade(trade).reverse()))
        civ.cache.updateCivResources()
    }

    /** Fresh trade-table maxima are stricter than the AI trade evaluator's 10% gold tolerance.
     * Gold quantities may be chosen freely in the UI; resource clicks add one copy at a time.
     * A list is validated before TradeOffersList can merge duplicates or overflow their amounts.
     */
    private fun validTerms(other: Civilization, trade: PeaceTrade): Boolean {
        if (!supportedTerms(trade.ourOffers) || !supportedTerms(trade.theirOffers)) return false
        val logic = TradeLogic(civ, other)
        fun validSide(terms: List<PeaceTradeTerm>, available: List<TradeOffer>): Boolean = terms.all { offer ->
            available.any { it.name == offer.name && it.type == offer.type &&
                offer.duration == it.duration && offer.amount <= it.amount }
        }
        return validSide(trade.ourOffers, logic.ourAvailableOffers) &&
            validSide(trade.theirOffers, logic.theirAvailableOffers) &&
            TradeEvaluation().isTradeValid(toTrade(trade), civ, other)
    }

    companion object {
        fun supportsTerm(term: PeaceTradeTerm): Boolean = when (term.type) {
            TradeOfferType.Treaty -> term.name == Constants.peaceTreaty
            TradeOfferType.Gold -> term.name == Constants.flatGold
            TradeOfferType.Gold_Per_Turn -> term.name == Constants.goldPerTurn
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> true
            else -> false
        }

        fun isSupportedTrade(trade: Trade): Boolean = supportedTerms(trade.ourOffers.map(::term)) &&
            supportedTerms(trade.theirOffers.map(::term))

        /** Routes supported categories through validation even if a stale UI draft has invalid
         * quantities, names, treaty symmetry or durations. Trades using additional features retain
         * their existing UI path rather than having those terms silently discarded.
         */
        fun usesSupportedPeaceTerms(trade: Trade): Boolean {
            val offers = trade.ourOffers + trade.theirOffers
            return offers.any { it.type == TradeOfferType.Treaty && it.name == Constants.peaceTreaty } &&
                offers.all { it.type in setOf(TradeOfferType.Gold, TradeOfferType.Gold_Per_Turn,
                    TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource) ||
                    it.type == TradeOfferType.Treaty && it.name == Constants.peaceTreaty }
        }

        private fun supportedTerms(terms: List<PeaceTradeTerm>): Boolean =
            terms.isNotEmpty() && terms.all { supportsTerm(it) && it.amount > 0 } &&
                terms.count { it.type == TradeOfferType.Treaty && it.name == Constants.peaceTreaty && it.amount == 1 } == 1 &&
                terms.map { it.type to it.name }.toSet().size == terms.size

        private fun term(offer: TradeOffer) = PeaceTradeTerm(offer.name, offer.type, offer.amount, offer.duration)
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun snapshot(trade: Trade) = PeaceTrade(immutable(trade.ourOffers.map(::term)), immutable(trade.theirOffers.map(::term)))
        private fun toTrade(trade: PeaceTrade) = Trade().apply {
            ourOffers.addAll(trade.ourOffers.map { it.toOffer() })
            theirOffers.addAll(trade.theirOffers.map { it.toOffer() })
        }
    }
}
