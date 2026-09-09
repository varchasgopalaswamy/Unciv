package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

/** Ordinary pillaging effects shared by unit actions and validated player operations. */
object UnitPillage {
    @Readonly
    fun canPillage(unit: MapUnit, tile: Tile): Boolean {
        if (unit.isTransported || unit.isCivilian() || unit.isDestroyed) return false
        if (!tile.canPillageTile()) return false
        if (unit.hasUnique(UniqueType.CannotPillage, checkCivInfoUniques = true)) return false
        val tileOwner = tile.getOwner()
        return tileOwner == null || unit.civ.isAtWarWith(tileOwner)
    }

    @Readonly
    fun movementCost(unit: MapUnit): Float =
        if (unit.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true)) 0f else 1f

    @Readonly
    fun healing(unit: MapUnit, tile: Tile): Int {
        val modifier = if (unit.hasUnique(UniqueType.HealingEffectsDoubled, checkCivInfoUniques = true)) 2 else 1
        return (baseHealing(unit, tile) * modifier).coerceAtMost(100 - unit.health)
    }

    @Readonly
    private fun baseHealing(unit: MapUnit, tile: Tile): Int {
        if (!tile.canPillageTileImprovement()) return 0
        var amount = 25f
        for (unique in unit.getMatchingUniques(UniqueType.PercentHealthFromPillaging, checkCivInfoUniques = true))
            amount *= unique.params[0].toPercent()
        return amount.toInt()
    }

    fun confirmation(improvementName: String) = "Are you sure you want to pillage this [$improvementName]?"

    /** Recheck before spending movement or rolling loot. Never declares war implicitly. */
    fun tryPillage(unit: MapUnit, tile: Tile): Boolean = synchronized(unit.civ.gameInfo) {
        if (unit.currentTile !== tile || !unit.hasMovement() || !canPillage(unit, tile))
            return@synchronized false
        val improvement = tile.getImprovementToPillage() ?: return@synchronized false
        val improvementName = improvement.name
        val healAmount = baseHealing(unit, tile)
        val destroysImprovement = improvement.hasUnique(UniqueType.DestroyedWhenPillaged)
        tile.getOwner()?.addNotification(
            "An enemy [${unit.baseUnit.name}] has pillaged our [$improvementName]",
            tile.position, NotificationCategory.War, "ImprovementIcons/$improvementName",
            NotificationIcon.War, unit.baseUnit.name
        )
        pillageLooting(tile, unit)
        tile.setPillaged()
        if (tile.resource != null) tile.getOwner()?.cache?.updateCivResources()
        val movementCost = movementCost(unit)
        if (movementCost > 0f) unit.useMovementPoints(movementCost)
        if (healAmount != 0) unit.healBy(healAmount)
        if (destroysImprovement) tile.removeImprovement()
        true
    }

    private fun pillageLooting(tile: Tile, unit: MapUnit) {
        val closestCity = unit.civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val improvement = tile.getImprovementToPillage()!!

        // Accumulate the loot
        var pillageYield = Stats()
        val stateForConditionals = unit.cache.state
        val random = Random(unit.civ.gameInfo.turns * unit.getTile().position.hashCode().toLong())
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldRandom, stateForConditionals)) {
            for ((stat, value) in unique.stats) {
                var yieldsToAdd = Stats()
                // Unique text says "approximately [X]", so we add 0..X twice - think an RPG's 2d12
                yieldsToAdd.add(stat, (random.nextInt((value + 1).toInt()) + random.nextInt((value + 1).toInt()).toFloat()))
                if (unique.isModifiedByGameSpeed())
                    yieldsToAdd *= unit.civ.gameInfo.speed.modifier
                if (unique.isModifiedByGameProgress())
                    yieldsToAdd *= unique.getGameProgressModifier(unit.civ)
                pillageYield.add(yieldsToAdd)
            }
        }
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldFixed, stateForConditionals)) {
            var yieldsToAdd = unique.stats
            if (unique.isModifiedByGameSpeed())
                yieldsToAdd *= unit.civ.gameInfo.speed.modifier
            if (unique.isModifiedByGameProgress())
                yieldsToAdd *= unique.getGameProgressModifier(unit.civ)
            pillageYield.add(yieldsToAdd)
        }

        //Multiply according to uniques
        for (unique in unit.getMatchingUniques(UniqueType.PercentYieldFromPillaging, checkCivInfoUniques = true)) {
            pillageYield *= unique.params[0].toPercent()
        }

        // Please no notification when there's no loot
        if (pillageYield.isEmpty()) return

        // Distribute the loot and keep record what went to civ/city for the notification(s)
        val globalPillageYield = Stats()
        val toCityPillageYield = Stats()
        for ((stat, value) in pillageYield) {
            if (stat in Stat.statsWithCivWideField) {
                unit.civ.addStat(stat, value.toInt())
                globalPillageYield[stat] += value
            }
            else if (closestCity != null) {
                closestCity.addStat(stat, value.toInt())
                toCityPillageYield[stat] += value
            }
        }

        // Now tell the user about the swag
        fun Stats.notify(suffix: String) {
            if (isEmpty()) return
            val text = "We have looted [${toStringWithoutIcons()}] from a [${improvement.name}]" + suffix
            unit.civ.addNotification(text, tile.position, NotificationCategory.War, "ImprovementIcons/${improvement.name}", NotificationIcon.War)
        }
        toCityPillageYield.notify(" which has been sent to [${closestCity?.name}]")
        globalPillageYield.notify("")
    }

}
