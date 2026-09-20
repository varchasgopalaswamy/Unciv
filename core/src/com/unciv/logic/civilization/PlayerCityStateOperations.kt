package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import java.util.Collections

/** Native city-state screen choices. Callers retain their local input/visibility checks.
 * Mutations re-create the offered action under the game monitor, so stale buttons
 * cannot spend unavailable Gold, bypass a cooldown, or gift a foreign unit.
 */
class PlayerCityStateOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    enum class Kind { GiftGold, GiftImprovement, PledgeProtection, RevokeProtection, GoldTribute, WorkerTribute, MakePeace, DeclareWar, DiplomaticMarriage, GiftUnit }

    data class Option(
        val name: String,
        val kind: Kind,
        val title: String,
        val available: Boolean,
        val reasons: List<String>,
        val gold: Int = 0,
        val influence: Float = 0f,
        val confirmation: List<String> = emptyList(),
        val position: HexCoord? = null,
        val improvement: String? = null,
    )

    data class Influence(val current: Float, val restingPoint: Float, val decayPerTurn: Float, val turnsToRelationshipChange: Int)

    private data class Action(val option: Option, val run: () -> Unit)

    private fun canInspect(other: Civilization) = !spectatorMode && civ.isMajorCiv() &&
        civ.gameInfo.civilizations.any { it === civ } && other !== civ && other.isCityState &&
        other.gameInfo === civ.gameInfo && civ.gameInfo.civilizations.any { it === other } &&
        civ.knows(other) && other.knows(civ)

    fun influence(other: Civilization): Influence? = synchronized(civ.gameInfo) {
        if (!canInspect(other)) return@synchronized null
        val manager = other.getDiplomacyManager(civ)!!
        Influence(manager.getInfluence(), manager.getCityStateInfluenceRestingPoint(),
            manager.getCityStateInfluenceDegrade(), manager.getTurnsToRelationshipChange())
    }

    fun options(other: Civilization): List<Option> = synchronized(civ.gameInfo) {
        immutable(actions(other).map { it.option })
    }

    fun tryAct(other: Civilization, name: String): Boolean = synchronized(civ.gameInfo) {
        val action = actions(other).singleOrNull { it.option.name == name && it.option.available }
            ?: return@synchronized false
        action.run()
        true
    }

    private fun reasons(other: Civilization) = arrayListOf<String>().apply {
        if (!PlayerOperations(civ, spectatorMode).canAct()) add("Not the active human player.")
        if (civ.isDefeated()) add("Your civilization has been defeated.")
        if (other.isDefeated()) add("The city-state has been defeated.")
    }

    private fun actions(other: Civilization): List<Action> {
        if (!canInspect(other)) return emptyList()
        val result = ArrayList<Action>()
        val functions = other.cityStateFunctions
        val ours = civ.getDiplomacyManager(other)!!
        val theirs = other.getDiplomacyManager(civ)!!
        fun peaceReasons() = reasons(other).apply {
            if (civ.isAtWarWith(other)) add("You must be at peace with the city-state.")
        }
        fun add(name: String, kind: Kind, title: String, reasons: List<String>, gold: Int = 0,
                influence: Float = 0f, confirmation: List<String> = emptyList(), position: HexCoord? = null,
                improvement: String? = null, run: () -> Unit) {
            result.add(Action(Option(name, kind, title, reasons.isEmpty(), immutable(reasons), gold, influence,
                immutable(confirmation), position, improvement), run))
        }
        for (amount in listOf(250, 500, 1000)) {
            val influence = functions.influenceGainedByGift(civ, amount)
            add("giftGold$amount", Kind.GiftGold, "Gift [$amount] gold (+[$influence] influence)",
                peaceReasons().apply { if (civ.gold < amount) add("Not enough Gold.") }, -amount, influence.toFloat()) {
                functions.receiveGoldGift(civ, amount)
            }
        }
        for (tile in other.cities.flatMap { it.getTiles().toList() }) {
            val resource = tile.tileResource ?: continue
            if (!other.canSeeResource(resource) || resource.resourceType == ResourceType.Bonus ||
                tile.improvement?.let(resource::isImprovedBy) == true) continue
            for (improvement in other.gameInfo.ruleset.tileImprovements.values) {
                if (improvement.turnsToBuild == -1 || !resource.isImprovedBy(improvement.name) ||
                    !tile.improvementFunctions.canBuildImprovement(improvement, other.state)) continue
                val reasons = reasons(other).apply {
                    if (theirs.getInfluence() < 60) add("Requires at least [60] Influence.")
                    if (civ.gold < 200) add("Not enough Gold.")
                }
                val name = "giftImprovement:${tile.position.x}:${tile.position.y}:${improvement.name}"
                add(name, Kind.GiftImprovement, "Build [${improvement.name}] on [${resource.name}] (200 Gold)",
                    reasons, -200, position = tile.position, improvement = improvement.name) {
                    civ.addGold(-200)
                    tile.stopWorkingOnImprovement()
                    tile.setImprovement(improvement)
                    other.cache.updateCivResources()
                }
            }
        }
        add("pledgeProtection", Kind.PledgeProtection, "Pledge to protect", reasons(other).apply {
            if (!functions.otherCivCanPledgeProtection(civ)) add("Protection cannot be pledged now.")
        }, confirmation = listOf("Declare Protection of [${other.civName}]?")) { functions.addProtectorCiv(civ) }
        add("revokeProtection", Kind.RevokeProtection, "Revoke Protection", reasons(other).apply {
            if (!functions.otherCivCanWithdrawProtection(civ)) add("Protection cannot be withdrawn now.")
        }, influence = -20f, confirmation = listOf("Revoke protection for [${other.civName}]?")) { functions.removeProtectorCiv(civ) }
        for (worker in listOf(false, true)) {
            val gold = if (worker) 0 else functions.goldGainedByTribute()
            val reasons = peaceReasons().apply {
                if (functions.getTributeWillingness(civ, worker) < 0) add("The city-state is unwilling to pay this tribute.")
            }
            add(if (worker) "workerTribute" else "goldTribute", if (worker) Kind.WorkerTribute else Kind.GoldTribute,
                if (worker) "Take worker (-50 Influence)" else "Take [$gold] gold (-15 Influence)",
                reasons, gold, if (worker) -50f else -15f) {
                if (worker) functions.tributeWorker(civ) else functions.tributeGold(civ)
            }
        }
        val relationshipsFixed = civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
        add("makePeace", Kind.MakePeace, "Negotiate Peace", reasons(other).apply {
            if (relationshipsFixed) add("Diplomatic relationships cannot change.")
            if (!civ.isAtWarWith(other)) add("Already at peace.")
            if (other.allyCiv?.let { civ.knows(it) && civ.isAtWarWith(it) } == true) add("You are at war with the city-state's ally.")
            if (ours.hasFlag(DiplomacyFlags.DeclaredWar)) add("Cannot negotiate peace for [${ours.getFlag(DiplomacyFlags.DeclaredWar)}] turns.")
        }, confirmation = listOf("Peace with [${other.civName}]?")) {
            val trade = TradeLogic(civ, other)
            trade.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
            trade.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
            trade.acceptTrade()
        }
        add("declareWar", Kind.DeclareWar, "Declare war", reasons(other).apply {
            if (relationshipsFixed) add("Diplomatic relationships cannot change.")
            if (!ours.canDeclareWar()) add("War cannot be declared while at war or while a peace treaty is active.")
        }, confirmation = PlayerDiplomacyOperations(civ, spectatorMode).declarationConfirmation(other).orEmpty()) { ours.declareWar() }
        if (civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) {
            val cost = functions.getDiplomaticMarriageCost()
            add("diplomaticMarriage", Kind.DiplomaticMarriage, "Diplomatic Marriage ([$cost] Gold)", reasons(other).apply {
                if (!functions.canBeMarriedBy(civ)) add("Diplomatic marriage is unavailable.")
            }, -cost) {
                val cities = other.cities.toList()
                functions.diplomaticMarriage(civ)
                for (city in cities) civ.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, city.id))
            }
        }
        return result
    }

    fun giftOption(unit: MapUnit): Option? = synchronized(civ.gameInfo) {
        if (!PlayerUnitOperations(civ).owns(unit)) return@synchronized null
        val recipient = unit.currentTile.getOwner() ?: return@synchronized null
        if (!canInspect(recipient)) return@synchronized null
        val special = unit.getMatchingUniques(UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true)
            .firstOrNull { unit.matchesFilter(it.params[1]) }
        if (!unit.isMilitary() && special == null) return@synchronized null
        val reasons = reasons(recipient).apply {
            if (recipient.isAtWarWith(civ)) add("You must be at peace with the city-state.")
            if (unit.isTransported) add("Transported units cannot be gifted.")
            if (!unit.hasMovement()) add("No movement remaining.")
        }
        Option("giftUnit", Kind.GiftUnit, "Gift unit to [${recipient.civName}]", reasons.isEmpty(), immutable(reasons),
            influence = special?.params?.get(0)?.toFloat() ?: 5f)
    }

    fun tryGiftUnit(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        val option = giftOption(unit)?.takeIf { it.available } ?: return@synchronized false
        val recipient = unit.currentTile.getOwner()!!
        recipient.getDiplomacyManager(civ)!!.addInfluence(option.influence)
        if (unit.isGreatPerson()) unit.destroy() else unit.gift(recipient)
        true
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}
