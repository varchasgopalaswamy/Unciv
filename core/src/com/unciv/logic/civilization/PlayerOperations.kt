package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Victory
import yairm210.purity.annotations.Readonly
import java.util.Collections

/** Validated operations for an ordinary human-controlled civilization.
 *
 * UI callers must additionally respect their local input-disabled state. Validation
 * and mutation share the game monitor, so callers that serialize on that monitor can
 * safely recheck a choice after presenting it. This does not make arbitrary direct
 * writes to engine objects thread-safe and is not a serialization boundary.
 */
class PlayerOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    @Readonly
    fun canAct(): Boolean = !spectatorMode && civ.isHuman() && civ.isMajorCiv() &&
        !civ.isDefeated() && civ.gameInfo.currentPlayer == civ.civID &&
        civ.gameInfo.currentPlayerCiv === civ && civ.gameInfo.civilizations.any { it === civ }

    fun tryRenameCity(city: City, name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || city.civ !== civ || civ.cities.none { it === city } || !isValidCityName(name))
            return@synchronized false
        city.name = name
        true
    }

    /** Replaces the research queue only when every prerequisite precedes its dependent. */
    fun trySetResearchQueue(names: List<String>): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || civ.tech.freeTechs > 0 || names.isEmpty() || names.size != names.toSet().size)
            return@synchronized false
        val scheduled = HashSet<String>()
        for (name in names) {
            val tech = civ.gameInfo.ruleset.technologies[name] ?: return@synchronized false
            if (civ.tech.isUnresearchable(tech) || civ.tech.isResearched(name) && !tech.isContinuallyResearchable())
                return@synchronized false
            if (tech.prerequisites.any { !civ.tech.isResearched(it) && it !in scheduled })
                return@synchronized false
            scheduled.add(name)
        }
        civ.tech.techsToResearch = ArrayList(names)
        civ.tech.updateResearchProgress()
        true
    }

    fun tryChooseFreeTechnology(name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || civ.tech.freeTechs <= 0 || name !in civ.gameInfo.ruleset.technologies ||
            !civ.tech.canBeResearched(name)) return@synchronized false
        civ.tech.getFreeTechnology(name)
        civ.tech.updateResearchProgress()
        true
    }

    @Readonly
    fun canAdoptPolicy(name: String): Boolean {
        val policy = civ.gameInfo.ruleset.policies[name] ?: return false
        return canAct() && civ.policies.canAdoptPolicy() && civ.policies.isAdoptable(policy)
    }

    fun tryAdoptPolicy(name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canAdoptPolicy(name)) return@synchronized false
        civ.policies.adopt(civ.gameInfo.ruleset.policies.getValue(name))
        true
    }

    fun tryChooseFreeGreatPerson(name: String): MapUnit? = synchronized(civ.gameInfo) {
        if (!canAct()) return@synchronized null
        civ.greatPeople.chooseFreeGreatPerson(name)
    }

    companion object {
        val informationalAlerts = setOf(
            AlertType.TechResearched, AlertType.GoldenAge, AlertType.StartIntro,
            AlertType.WonderBuilt, AlertType.FirstContact, AlertType.WarDeclaration,
            AlertType.Defeated, AlertType.GameHasBeenWon,
        )

        /** Matches the ordinary city-name popup, including its single-line text field. */
        @Readonly
        fun isValidCityName(name: String): Boolean = name.isNotEmpty() && name.length <= 32 &&
            name.none { it in "[]{}\"\\<>" || it.code in 0..31 || it.code in 127..159 }
    }

    /** Construction choices which do not require a separate tile-placement decision. */
    @Readonly
    fun queueableConstructions(city: City): List<String> {
        if (city.civ !== civ || civ.cities.none { it === city } || city.isPuppet) return emptyList()
        val ruleset = civ.gameInfo.ruleset
        val candidates: List<IConstruction> = ruleset.buildings.values + ruleset.units.values +
            PerpetualConstruction.perpetualConstructionsMap.values
        return candidates.filter {
            it.name.isNotEmpty() && city.cityConstructions.canAddToQueue(it) &&
                (it !is Building || it.getImprovementToCreate(ruleset, civ) == null)
        }.map { it.name }
    }

    fun tryQueueConstruction(city: City, name: String): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || name !in queueableConstructions(city)) return@synchronized false
        city.cityConstructions.addToQueue(name)
        city.cityStats.update()
        true
    }

    /** Opening the policy picker permits deferring a paid choice; free choices still remain due. */
    fun tryDismissPolicyPicker(): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || !civ.policies.shouldShowPolicyPicker()) return@synchronized false
        civ.policies.shouldOpenPolicyPicker = false
        true
    }

    /** The introduction already earned by meeting a civilization, available even out of turn.
     *
     * Discovery performs the meeting effects before adding this alert. Reading or
     * acknowledging it must not meet the civilization again or repeat city-state gifts.
     */
    @Readonly
    fun firstContactIntroduction(alert: PopupAlert): FirstContactIntroduction? = synchronized(civ.gameInfo) {
        if (spectatorMode || civ.isSpectator() || alert.type != AlertType.FirstContact ||
            civ.gameInfo.civilizations.none { it === civ } || civ.popupAlerts.none { it === alert })
            return@synchronized null
        val other = civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value }
            ?: return@synchronized null
        if (!civ.knows(other) || other.isSpectator() || other.isBarbarian) return@synchronized null
        FirstContactIntroduction(
            civilizationId = other.civID,
            civilizationName = other.civName,
            leaderName = other.getLeaderDisplayName(),
            message = if (other.isCityState) "We have encountered the City-State of [${other.nation.name}]!"
                else other.nation.introduction,
            acknowledgement = if (other.isCityState) "Excellent!" else "A pleasure to meet you.",
        )
    }

    /** All text presented by the owned informational alert, available even out of turn.
     *
     * The alert records an event that has already happened. Its target must still be
     * valid, but reading it never repeats the event or grants any game effects.
     * This composes the desktop description formatters, which are not purity-annotated;
     * tests verify that reading the content does not change serialized game state.
     */
    fun informationalPopupContent(alert: PopupAlert): InformationalPopupContent? = synchronized(civ.gameInfo) {
        if (spectatorMode || civ.isSpectator() || civ.gameInfo.civilizations.none { it === civ } ||
            civ.popupAlerts.none { it === alert }) return@synchronized null
        when (alert.type) {
            AlertType.Defeated -> {
                val other = civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value }
                    ?: return@synchronized null
                InformationalPopupContent(
                    title = other.getLeaderDisplayName(),
                    paragraphs = immutableParagraphs(other.nation.defeated),
                    acknowledgement = "Farewell.",
                )
            }
            AlertType.GameHasBeenWon -> {
                val victory = civ.gameInfo.victoryData ?: return@synchronized null
                InformationalPopupContent(
                    title = "[${victory.winningCivObject.civName}] has won a [${victory.victoryType}] Victory!",
                    paragraphs = emptyList(),
                    acknowledgement = Constants.close,
                )
            }
            AlertType.WarDeclaration -> {
                val other = civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value }
                    ?: return@synchronized null
                if (!civ.knows(other) || other.isDefeated() || other.isSpectator() || other.isBarbarian)
                    return@synchronized null
                InformationalPopupContent(
                    title = other.getLeaderDisplayName(),
                    paragraphs = immutableParagraphs("DECLARATION OF WAR", other.nation.declaringWar)
                        .let { Collections.unmodifiableList(it.filter(String::isNotEmpty)) },
                    acknowledgement = "Very well.",
                    additionalAcknowledgements = immutableParagraphs("You'll pay for this!"),
                )
            }
            AlertType.FirstContact -> {
                val introduction = firstContactIntroduction(alert) ?: return@synchronized null
                InformationalPopupContent(
                    title = introduction.leaderName,
                    paragraphs = immutableParagraphs(introduction.message),
                    acknowledgement = introduction.acknowledgement,
                )
            }
            AlertType.StartIntro -> InformationalPopupContent(
                title = civ.getLeaderDisplayName(),
                paragraphs = immutableParagraphs(civ.nation.startIntroPart1, civ.nation.startIntroPart2),
                acknowledgement = "Let's begin!",
            )
            AlertType.TechResearched -> {
                val technology = civ.gameInfo.ruleset.technologies[alert.value] ?: return@synchronized null
                InformationalPopupContent(
                    title = technology.name,
                    paragraphs = immutableParagraphs(technology.getDescription(civ)),
                    quote = technology.quote,
                    acknowledgement = Constants.close,
                )
            }
            AlertType.WonderBuilt -> {
                val wonder = civ.gameInfo.ruleset.buildings[alert.value] ?: return@synchronized null
                if (!wonder.isWonder) return@synchronized null
                InformationalPopupContent(
                    title = wonder.name,
                    paragraphs = immutableParagraphs(wonder.getShortDescription()),
                    quote = wonder.quote.takeIf { it.isNotEmpty() },
                    acknowledgement = Constants.close,
                )
            }
            AlertType.GoldenAge -> InformationalPopupContent(
                title = "GOLDEN AGE",
                paragraphs = immutableParagraphs("Your citizens have been happy with your rule for so long that the empire enters a Golden Age!"),
                acknowledgement = Constants.close,
            )
            else -> null
        }
    }

    private fun immutableParagraphs(vararg text: String): List<String> =
        Collections.unmodifiableList(text.toList())

    /** The victory screen's result is public even to defeated players and out of turn. */
    fun gameResult(): PlayerGameResult? = synchronized(civ.gameInfo) {
        if (spectatorMode || civ.isSpectator() || civ.gameInfo.civilizations.none { it === civ })
            return@synchronized null
        val data = civ.gameInfo.victoryData
        if (data == null) {
            if (!civ.isDefeated()) return@synchronized null
            return@synchronized PlayerGameResult("Defeat", null, null, null, null,
                Victory().defeatString, emptyList())
        }
        val won = data.winningCiv == civ.civID
        val victory = civ.gameInfo.ruleset.victories[data.victoryType] ?: Victory()
        PlayerGameResult(
            outcome = if (won) "Victory" else "Defeat",
            winningCivilizationId = data.winningCiv,
            winningCivilizationName = data.winningCivObject.civName,
            victoryType = data.victoryType,
            victoryTurn = data.victoryTurn,
            title = if (won) "You have won a [${data.victoryType}] Victory!"
                else "[${data.winningCivObject.civName}] has won a [${data.victoryType}] Victory!",
            paragraphs = immutableParagraphs(if (won) victory.victoryString else victory.defeatString),
        )
    }

    fun tryAcknowledgeAlert(alert: PopupAlert): Boolean = synchronized(civ.gameInfo) {
        // Victory remains dismissible after the viewer is eliminated or another player is active.
        val canDismissResult = alert.type == AlertType.GameHasBeenWon && !spectatorMode &&
            civ.isHuman() && civ.isMajorCiv() && civ.gameInfo.civilizations.any { it === civ }
        if ((!canAct() && !canDismissResult) || informationalPopupContent(alert) == null)
            return@synchronized false
        civ.popupAlerts.remove(alert)
        true
    }
}
