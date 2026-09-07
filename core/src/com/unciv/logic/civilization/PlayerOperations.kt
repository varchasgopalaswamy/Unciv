package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import yairm210.purity.annotations.Readonly

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
        val informationalAlerts = setOf(AlertType.TechResearched, AlertType.GoldenAge, AlertType.StartIntro, AlertType.WonderBuilt)

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
        true
    }

    /** Opening the policy picker permits deferring a paid choice; free choices still remain due. */
    fun tryDismissPolicyPicker(): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || !civ.policies.shouldShowPolicyPicker()) return@synchronized false
        civ.policies.shouldOpenPolicyPicker = false
        true
    }

    fun tryAcknowledgeAlert(alert: PopupAlert): Boolean = synchronized(civ.gameInfo) {
        if (!canAct() || alert.type !in informationalAlerts || civ.popupAlerts.none { it === alert })
            return@synchronized false
        civ.popupAlerts.remove(alert)
        true
    }
}
