package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import java.util.Collections

/** The native water-resource improvement action, including consumption of its builder.
 * An offered button is revalidated on the unit's current tile when executed.
 */
class PlayerWaterImprovementOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class Option(
        val name: String,
        val title: String,
        val resource: String,
        val available: Boolean,
        val reasons: List<String>,
        val consumesUnit: Boolean = true,
    )

    fun option(unit: MapUnit): Option? = synchronized(civ.gameInfo) {
        if (spectatorMode || civ.isSpectator() || !PlayerUnitOperations(civ).owns(unit) ||
            civ.gameInfo.civilizations.none { it === civ }) return@synchronized null
        val tile = unit.currentTile
        if (!tile.isWater || !unit.hasUnique(UniqueType.CreateWaterImprovements)) return@synchronized null
        val resource = tile.tileResource?.takeIf { civ.canSeeResource(it) } ?: return@synchronized null
        val improvement = resource.getImprovingImprovement(tile, unit.cache.state) ?: return@synchronized null
        val reasons = ArrayList<String>()
        if (!PlayerOperations(civ, spectatorMode).canAct()) reasons.add("This player cannot act now")
        if (!unit.hasMovement()) reasons.add("No movement remaining")
        Option(improvement.name, "Create [${improvement.name}]", resource.name, reasons.isEmpty(),
            Collections.unmodifiableList(reasons))
    }

    fun tryCreate(unit: MapUnit, name: String): Boolean = synchronized(civ.gameInfo) {
        val offered = option(unit)?.takeIf { it.name == name && it.available } ?: return@synchronized false
        val improvement = civ.gameInfo.ruleset.tileImprovements.getValue(offered.name)
        unit.currentTile.setImprovement(improvement, civ, unit)
        unit.destroy()
        true
    }
}
