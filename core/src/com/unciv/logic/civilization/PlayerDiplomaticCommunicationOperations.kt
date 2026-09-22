package com.unciv.logic.civilization

import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.civilization.diplomacy.WarType
import java.util.Collections

data class DiplomaticResponseOption(
    val name: String,
    val text: String,
    val available: Boolean = true,
    val consequences: List<String> = emptyList(),
)

data class DiplomaticMessageDecision(
    val civilizationId: String,
    val content: InformationalPopupContent,
    val options: List<DiplomaticResponseOption>,
    val voice: String? = null,
)

data class DiplomaticDemandOption(val name: String, val text: String, val action: PlayerDiplomaticAction)

/** Native diplomatic messages shared by the diplomacy screen and other player interfaces.
 * Reads are detached and allowed out of turn. Mutations require the active player
 * and revalidate both an owned popup's identity and its currently offered choice.
 */
class PlayerDiplomaticCommunicationOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    private fun visible(other: Civilization, cityStateMessage: Boolean = false) =
        !spectatorMode && civ.isMajorCiv() && (other.isMajorCiv() || cityStateMessage && other.isCityState) &&
        civ !== other && civ.gameInfo.civilizations.any { it === civ } &&
        civ.gameInfo.civilizations.any { it === other } && civ.knows(other) && other.knows(civ)

    fun denounceOption(other: Civilization): PlayerDiplomaticAction? = synchronized(civ.gameInfo) {
        if (!visible(other)) return@synchronized null
        val manager = civ.getDiplomacyManager(other)!!
        val reasons = arrayListOf<String>()
        if (!PlayerOperations(civ, spectatorMode).canAct()) reasons.add("Not the active human player.")
        if (civ.isDefeated() || other.isDefeated()) reasons.add("A civilization has been defeated.")
        if (civ.isAtWarWith(other)) reasons.add("Already at war.")
        if (manager.hasFlag(DiplomacyFlags.Denunciation)) reasons.add("A denunciation is already active.")
        if (manager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) reasons.add("A declaration of friendship is active.")
        PlayerDiplomaticAction(reasons.isEmpty(), immutable(reasons))
    }

    fun tryDenounce(other: Civilization): Boolean = synchronized(civ.gameInfo) {
        if (denounceOption(other)?.available != true) return@synchronized false
        civ.getDiplomacyManager(other)!!.denounce()
        true
    }

    fun demands(other: Civilization): List<DiplomaticDemandOption> = synchronized(civ.gameInfo) {
        if (!visible(other)) return@synchronized emptyList()
        immutable(Demand.entries.filter { it.show(civ) }.map { demand ->
            val reasons = arrayListOf<String>()
            if (!PlayerOperations(civ, spectatorMode).canAct()) reasons.add("Not the active human player.")
            if (civ.isDefeated() || other.isDefeated()) reasons.add("A civilization has been defeated.")
            if (other.popupAlerts.any { it.type == demand.demandAlert && it.value == civ.civID })
                reasons.add("This demand is already pending.")
            if (civ.getDiplomacyManager(other)!!.hasFlag(demand.agreedToDemand))
                reasons.add("They already agreed to this demand.")
            DiplomaticDemandOption(demand.name, demand.demandText, PlayerDiplomaticAction(reasons.isEmpty(), immutable(reasons)))
        })
    }

    fun tryDemand(other: Civilization, demand: Demand): Boolean = synchronized(civ.gameInfo) {
        if (demands(other).none { it.name == demand.name && it.action.available }) return@synchronized false
        other.popupAlerts.add(PopupAlert(demand.demandAlert, civ.civID))
        true
    }

    fun decision(alert: PopupAlert): DiplomaticMessageDecision? = synchronized(civ.gameInfo) {
        if (spectatorMode || !civ.isMajorCiv() || civ.popupAlerts.none { it === alert } || alert.type !in supportedAlerts)
            return@synchronized null
        val other = civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value.substringBefore('@') }
            ?: return@synchronized null
        // City-states send border and stolen-tile notices, but cannot participate
        // in the major-civilization demand and denunciation actions.
        if (!visible(other, alert.type in cityStateAlerts)) return@synchronized null
        fun response(text: List<String>, options: List<DiplomaticResponseOption>, voice: String? = null) =
            DiplomaticMessageDecision(other.civID,
                InformationalPopupContent(other.getLeaderDisplayName(), immutable(text), options.last().text),
                immutable(options.map { it.copy(consequences = immutable(it.consequences)) }), voice)
        val acknowledge = DiplomaticResponseOption("Acknowledge", "Very well.")
        if (other.isDefeated()) return@synchronized response(listOf("This diplomatic message is no longer active."), listOf(acknowledge))
        val diplomatic = civ.getDiplomacyManager(other)!!
        val friendlyText = other.getDiplomacyManager(civ)!!.isRelationshipLevelGE(RelationshipLevel.Competitor)
        val demand = Demand.entries.firstOrNull { it.demandAlert == alert.type }
            ?: Demand.DontSpyOnUs.takeIf { alert.type == AlertType.SpyingOnUsDespiteOurPromise }
        if (demand != null) {
            val war = demand == Demand.DoNotAttackUs
            return@synchronized response(listOf(demand.demandText), listOf(
                DiplomaticResponseOption("Accept", demand.acceptDemandText, consequences = listOf("Record the native promise and diplomatic response.")),
                DiplomaticResponseOption("Refuse", demand.refuseDemandText,
                    available = !war || diplomatic.canDeclareWar(),
                    consequences = if (war) PlayerDiplomacyOperations(civ).declarationConfirmation(other).orEmpty()
                        else listOf("Record the native refusal and diplomatic response."))))
        }
        val violation = Demand.entries.firstOrNull { it.violationDiscoveredAlert == alert.type }
        if (violation != null) return@synchronized response(listOf(violation.violationNoticedText), listOf(acknowledge))
        when (alert.type) {
            AlertType.BorderConflict -> response(listOf("Remove your troops in our border immediately!"),
                listOf(DiplomaticResponseOption("Acknowledge", "Sorry."), DiplomaticResponseOption("Protest", "Never!")))
            AlertType.TilesStolen -> response(listOf("Those lands were not yours to take. This has not gone unnoticed."),
                listOf(DiplomaticResponseOption("Acknowledge", "Close")))
            AlertType.Denounced -> response(listOf("DENOUNCEMENT", if (friendlyText)
                other.nation.neutralDenouncing.ifEmpty { "You have violated our bond of trust. This is intolerable!" }
                else other.nation.hateDenouncing.ifEmpty { "You are a scourge upon this earth. I denounce you!" }),
                listOfNotNull(DiplomaticResponseOption("DeclareWar", "THIS MEANS WAR! (Declare war)",
                    consequences = PlayerDiplomacyOperations(civ).declarationConfirmation(other).orEmpty()).takeIf { diplomatic.canDeclareWar() }, acknowledge),
                if (friendlyText) "neutralDenouncing" else "hateDenouncing")
            AlertType.AcceptingDemand -> response(listOf("ACCEPTING DEMAND", other.nation.acceptingDemand.ifEmpty {
                "We will comply, but our consent is given grudgingly." }), listOf(acknowledge), "acceptingDemand")
            AlertType.RejectingDemand -> response(listOf("REJECTING DEMAND", if (friendlyText)
                other.nation.neutralRejectingDemand.ifEmpty { "Your demands are in poor taste. We shall decide this matter on our own." }
                else other.nation.hateRejectingDemand.ifEmpty { "Did you really expect us to bend to such brazen demands?" }),
                listOf(DiplomaticResponseOption("Protest", "You'll pay for this!"), acknowledge),
                if (friendlyText) "neutralRejectingDemand" else "hateRejectingDemand")
            else -> {
                val minor = incidentCityState(alert) ?: return@synchronized null
                val name = if (civ.knows(minor)) minor.civName else "An unknown city-state"
                val neutral = other.getDiplomacyManager(civ)!!.isRelationshipLevelGE(RelationshipLevel.Neutral)
                val text = when {
                    alert.type == AlertType.BulliedProtectedMinor && neutral -> "I've been informed that my armies have taken tribute from [$name], a city-state under your protection.\nI assure you, this was quite unintentional, and I hope that this does not serve to drive us apart."
                    alert.type == AlertType.BulliedProtectedMinor -> "We asked [$name] for a tribute recently and they gave in.\nYou promised to protect them from such things, but we both know you cannot back that up."
                    neutral -> "It's come to my attention that I may have attacked [$name].\nWhile it was not my goal to be at odds with your empire, this was deemed a necessary course of action."
                    else -> "I thought you might like to know that I've launched an invasion of one of your little pet states.\nThe lands of [$name] will make a fine addition to my own."
                }
                response(listOf(text), listOfNotNull(
                    DiplomaticResponseOption("DeclareWar", "THIS MEANS WAR!",
                        consequences = PlayerDiplomacyOperations(civ).declarationConfirmation(other).orEmpty() + "Gain [20] influence for defending the city-state.").takeIf { diplomatic.canDeclareWar() },
                    DiplomaticResponseOption("SideWithCityState", "You'll pay for this!", consequences = listOf("Side with the city-state; no declaration of war.")),
                    DiplomaticResponseOption("WithdrawProtection", "Very well.", consequences = listOf("Withdraw protection and lose [20] influence."))))
            }
        }
    }

    fun tryRespond(alert: PopupAlert, choice: String): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val decision = decision(alert) ?: return@synchronized false
        if (decision.options.none { it.name == choice && it.available }) return@synchronized false
        val other = civ.gameInfo.getCivilization(decision.civilizationId)
        val manager = civ.getDiplomacyManager(other)!!
        val demand = Demand.entries.firstOrNull { it.demandAlert == alert.type }
            ?: Demand.DontSpyOnUs.takeIf { alert.type == AlertType.SpyingOnUsDespiteOurPromise }
        when (choice) {
            "Accept" -> manager.agreeToDemand(checkNotNull(demand))
            "Refuse" -> manager.refuseDemand(checkNotNull(demand)) // DoNotAttackUs declares war exactly once.
            "DeclareWar" -> {
                val minor = incidentCityState(alert)
                if (minor == null) manager.declareWar()
                else {
                    manager.sideWithCityState()
                    val reason = if (alert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
                    manager.declareWar(DeclareWarReason(reason, minor))
                    minor.getDiplomacyManager(civ)!!.influence += 20f
                }
            }
            "SideWithCityState" -> manager.sideWithCityState()
            "WithdrawProtection" -> {
                val minor = checkNotNull(incidentCityState(alert))
                civ.addNotification("You have broken your Pledge to Protect [${minor.civName}]!",
                    minor.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, minor.civName)
                minor.cityStateFunctions.removeProtectorCiv(civ, forced = true)
            }
        }
        civ.popupAlerts.remove(alert)
        true
    }

    private fun incidentCityState(alert: PopupAlert): Civilization? {
        if (alert.type !in incidentAlerts) return null
        val id = alert.value.substringAfter('@', "")
        return civ.gameInfo.civilizations.firstOrNull { it.civID == id && it.isCityState && it.getDiplomacyManager(civ) != null }
    }

    companion object {
        private val cityStateAlerts = setOf(AlertType.BorderConflict, AlertType.TilesStolen)
        val incidentAlerts = setOf(AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor)
        val supportedAlerts = incidentAlerts + setOf(AlertType.Denounced, AlertType.AcceptingDemand, AlertType.RejectingDemand, AlertType.BorderConflict, AlertType.TilesStolen) +
            Demand.entries.flatMap { listOf(it.demandAlert, it.violationDiscoveredAlert) }
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}
