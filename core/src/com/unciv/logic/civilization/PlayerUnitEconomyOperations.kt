package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitPillage
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import java.util.Collections

/** Validated ordinary land-unit upgrades, pillaging and disbanding.
 *
 * Paid upgrades replace a unit on its current tile, retaining its identity and
 * statistics through the normal upgrade manager. Domain-changing upgrades and
 * upgrades requiring relocation remain separate from these in-place operations.
 * Options contain detached public rule descriptions and the owning player's costs.
 */
class PlayerUnitEconomyOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class UpgradeOption(
        val targetName: String,
        val title: String,
        val description: String,
        val strength: Int,
        val rangedStrength: Int,
        val movement: Int,
        val range: Int,
        val requiredTechnologies: List<String>,
        val goldCost: Int,
        val resourceRequirements: Map<String, Int>,
        val available: Boolean,
        val reasons: List<String>,
        val confirmation: String? = null,
    )

    data class PillageOption(
        val improvementName: String,
        val title: String,
        val movementCost: Float,
        val healing: Int,
        val available: Boolean,
        val reasons: List<String>,
        val confirmation: String,
    )

    data class DisbandOption(val gold: Int, val available: Boolean, val reasons: List<String>, val confirmation: String)

    private fun canInspect(unit: MapUnit): Boolean = !spectatorMode && !civ.isSpectator() &&
        unit.baseUnit.isLandUnit && civ.gameInfo.civilizations.any { it === civ } && PlayerUnitOperations(civ).owns(unit)

    private fun actionReasons(unit: MapUnit): MutableList<String> = mutableListOf<String>().apply {
        if (!PlayerOperations(civ, spectatorMode).canAct()) add("This player cannot act now")
        if (!unit.hasMovement()) add("No movement remaining")
    }

    private fun targets(unit: MapUnit): List<BaseUnit> {
        val names = unit.baseUnit.getMatchingUniques(UniqueType.CanUpgrade, unit.cache.state).map { it.params[0] }.toList() +
            listOfNotNull(unit.baseUnit.upgradesTo)
        return names.map(civ::getEquivalentUnit).distinctBy { it.name }.filter { it.name != unit.name && it.isLandUnit }
    }

    /** Checks only the replacement's ability to occupy this already-visible tile.
     * No registration, unit-id allocation, exploration or placement search occurs.
     */
    fun canUpgradeInPlace(unit: MapUnit, targetName: String): Boolean {
        if (!canInspect(unit)) return false
        val target = targets(unit).singleOrNull { it.name == targetName } ?: return false
        val replacement = target.newMapUnit(civ, unit.id)
        replacement.currentTile = unit.currentTile
        replacement.cache.state = GameContext(replacement)
        // The old unit will be removed first. Never ignore a different unit's slot,
        // including civilian-to-military upgrades on an escorted tile.
        val occupant = if (replacement.isCivilian()) unit.currentTile.civilianUnit else unit.currentTile.militaryUnit
        if (occupant != null && occupant !== unit) return false
        return replacement.movement.canMoveTo(unit.currentTile, allowSwap = true, includeOtherEscortUnit = false)
    }

    fun upgrades(unit: MapUnit): List<UpgradeOption> {
        if (!canInspect(unit)) return emptyList()
        return immutable(targets(unit).map { target ->
            val cost = unit.upgrade.getCostOfUpgrade(target)
            val resources = target.getResourceRequirementsPerTurn(unit.cache.state).mapValues { (name, amount) ->
                (amount - unit.getResourceRequirementsPerTurn()[name]).coerceAtLeast(0)
            }.filterValues { it > 0 }
            val requirements = resources.entries.joinToString { "${it.value} {${it.key}}".tr() }
            val title = if (requirements.isEmpty()) "Upgrade to [${target.name}] ([$cost] gold)"
                else "Upgrade to [${target.name}]\n([$cost] gold, [$requirements])"
            val reasons = actionReasons(unit)
            if (unit.currentTile.getOwner() !== civ) reasons.add("The unit must be in your territory")
            if (unit.isEmbarked()) reasons.add("Embarked units cannot upgrade")
            if (civ.gold < cost) reasons.add("Not enough gold")
            if (!canUpgradeInPlace(unit, target.name)) reasons.add("The upgraded unit cannot remain on this tile")
            if (!unit.upgrade.canUpgrade(target)) {
                reasons.addAll(target.getRejectionReasons(civ, additionalResources = unit.getResourceRequirementsPerTurn())
                    .filterNot { it.isConstructionRejection() || it.type == RejectionReasonType.Obsoleted }
                    .map { if (it.shouldShow || it.type == RejectionReasonType.RequiresTech) it.errorMessage else "Unit requirements are not met" }.toList())
            }
            UpgradeOption(target.name, title, target.getShortDescription(), target.strength, target.rangedStrength,
                target.movement, target.range, immutable(target.requiredTechs().toList()), cost,
                Collections.unmodifiableMap(LinkedHashMap(resources)), reasons.isEmpty(), immutable(reasons.distinct()))
        })
    }

    fun tryUpgrade(unit: MapUnit, targetName: String): MapUnit? = synchronized(civ.gameInfo) {
        val option = upgrades(unit).singleOrNull { it.targetName == targetName && it.available } ?: return@synchronized null
        val target = civ.gameInfo.ruleset.units.getValue(option.targetName)
        val id = unit.id
        val tile = unit.currentTile
        unit.upgrade.performUpgrade(target, isFree = false, goldCostOfUpgrade = option.goldCost)
        checkNotNull(civ.units.getCivUnits().singleOrNull { it.id == id && it.baseUnit === target && it.currentTile === tile }) {
            "Validated in-place upgrade failed"
        }
    }

    fun pillage(unit: MapUnit): PillageOption? {
        if (!canInspect(unit)) return null
        val tile = unit.currentTile
        val improvement = tile.getImprovementToPillageName() ?: return null
        val reasons = actionReasons(unit)
        if (unit.isCivilian()) reasons.add("Civilian units cannot pillage")
        if (unit.isTransported) reasons.add("Transported units cannot pillage")
        if (unit.hasUnique(UniqueType.CannotPillage, checkCivInfoUniques = true)) reasons.add("This unit cannot pillage")
        val owner = tile.getOwner()
        if (owner === civ) reasons.add("You cannot pillage your own territory")
        else if (owner != null && !civ.isAtWarWith(owner)) reasons.add("You must already be at war with the tile owner")
        return PillageOption(improvement, "Pillage [$improvement]", UnitPillage.movementCost(unit),
            UnitPillage.healing(unit, tile), reasons.isEmpty(), immutable(reasons),
            UnitPillage.confirmation(improvement))
    }

    fun tryPillage(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (pillage(unit)?.available != true) return@synchronized false
        UnitPillage.tryPillage(unit, unit.currentTile)
    }

    fun disband(unit: MapUnit): DisbandOption? {
        if (!canInspect(unit)) return null
        val ownTerritory = unit.currentTile.getOwner() === civ
        val gold = if (ownTerritory) unit.baseUnit.getDisbandGold(civ) else 0
        val reasons = actionReasons(unit)
        return DisbandOption(gold, reasons.isEmpty(), immutable(reasons), disbandConfirmation(unit))
    }

    fun tryDisband(unit: MapUnit): Boolean = synchronized(civ.gameInfo) {
        if (disband(unit)?.available != true) return@synchronized false
        unit.disband()
        civ.updateStatsForNextTurn()
        true
    }

    companion object {
        /** Shared with the desktop confirmation, including the existing evacuation rules. */
        fun disbandConfirmation(unit: MapUnit): String {
            val base = if (unit.currentTile.getOwner() === unit.civ)
                "Disband this unit for [${unit.baseUnit.getDisbandGold(unit.civ)}] gold?"
            else "Do you really want to disband this unit?"
            val passengers = unit.currentTile.getUnits().count { it.isTransported && unit.isTransportTypeOf(it) }
            return if (passengers == 0) base else base + "\n" +
                "Transported units will try to evacuate; units that cannot escape will also be disbanded."
        }
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
