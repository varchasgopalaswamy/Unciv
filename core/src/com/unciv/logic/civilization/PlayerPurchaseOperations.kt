package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import java.util.Collections

/** Gold purchases with the same requirements as the city screen.
 *
 * Confirmation callers can supply the displayed price so a changed price is rejected
 * under the game monitor. Purchases needing an improvement placement picker and other
 * currencies retain their separate city-screen workflows.
 */
class PlayerPurchaseOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class ConstructionOption(
        val name: String,
        val kind: String,
        val description: String,
        val goldCost: Int?,
        val resourceRequirements: Map<String, Int>,
        val requiredTechnologies: List<String>,
        val available: Boolean,
        val unavailableReasons: List<String>,
        val confirmation: String?,
    )

    data class TileOption(
        val x: Int,
        val y: Int,
        val goldCost: Int,
        val available: Boolean,
        val unavailableReasons: List<String>,
        val confirmation: String?,
    )

    @Readonly
    private fun owns(city: City): Boolean = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ } && city.civ === civ && civ.cities.any { it === city }

    @Readonly
    private fun actionReasons(): List<String> =
        if (PlayerOperations(civ, spectatorMode).canAct()) emptyList() else listOf("This player cannot act now")

    @Readonly
    private fun constructions(): Sequence<INonPerpetualConstruction> =
        civ.gameInfo.ruleset.units.values.asSequence() + civ.gameInfo.ruleset.buildings.values.asSequence()

    @Readonly
    private fun isShown(city: City, construction: INonPerpetualConstruction): Boolean =
        construction.name in city.cityConstructions.constructionQueue || construction.shouldBeDisplayed(city.cityConstructions)

    fun constructionOptions(city: City): List<ConstructionOption> {
        if (!owns(city)) return emptyList()
        return frozen(constructions().filter { isShown(city, it) }
            .sortedBy { it.name }.map { constructionOption(city, it) }.toList())
    }

    @Readonly
    fun canBuyConstruction(city: City, name: String, expectedGoldCost: Int? = null): Boolean {
        if (!owns(city)) return false
        val construction = constructions().singleOrNull { it.name == name } ?: return false
        if (!isShown(city, construction)) return false
        val cost = goldCost(city, construction)
        return constructionReasons(city, construction, cost).isEmpty() && (expectedGoldCost == null || expectedGoldCost == cost)
    }

    @Readonly
    private fun goldCost(city: City, construction: INonPerpetualConstruction): Int? =
        if (construction.canBePurchasedWithStat(city, Stat.Gold)) construction.getStatBuyCost(city, Stat.Gold) else null

    @Readonly
    private fun constructionReasons(city: City, construction: INonPerpetualConstruction, cost: Int?): List<String> {
        @LocalState val reasons = actionReasons().toMutableList()
        if (city.isPuppet && !city.getMatchingUniques(UniqueType.MayBuyConstructionsInPuppets).any())
            reasons.add("Cannot purchase in a puppet city")
        if (city.isInResistance()) reasons.add("The city is in resistance")
        reasons.addAll(construction.getRejectionReasons(city.cityConstructions)
            .filter { it.type != RejectionReasonType.Unbuildable }
            .map { if (it.shouldShow) it.errorMessage else "Construction requirements are not met" }.toList())
        if (cost == null) reasons.add("Cannot purchase with Gold")
        if (construction is Building && construction.hasCreateOneImprovementUnique())
            reasons.add("This construction requires a tile placement choice")
        if (construction is BaseUnit && !city.canPlaceNewUnit(construction)) reasons.add("Move unit out of city first")
        if (cost != null && !civ.gameInfo.gameParameters.godMode && city.getStatReserve(Stat.Gold) < cost)
            reasons.add("Not enough gold")
        return reasons.distinct()
    }

    private fun constructionOption(city: City, construction: INonPerpetualConstruction): ConstructionOption {
        val cost = goldCost(city, construction)
        val reasons = constructionReasons(city, construction, cost)
        val description = when (construction) {
            is BaseUnit -> construction.getShortDescription()
            is Building -> construction.getShortDescription(multiline = true)
            else -> construction.name
        }
        return ConstructionOption(construction.name, if (construction is BaseUnit) "UNIT" else "BUILDING", description,
            cost, Collections.unmodifiableMap(LinkedHashMap(construction.getResourceRequirementsPerTurn(city.state))),
            frozen(construction.requiredTechs().toList()), reasons.isEmpty(), frozen(reasons.distinct()),
            cost?.let { "Currently you have [${civ.gold}] [Gold].\n\nWould you like to purchase [${construction.name}] for [$it] [${Stat.Gold.character}]?" })
    }

    fun tryBuyConstruction(city: City, name: String, expectedGoldCost: Int? = null, queuePosition: Int = -1): Boolean = synchronized(civ.gameInfo) {
        if (!owns(city) || !PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val construction = constructions().singleOrNull { it.name == name } ?: return@synchronized false
        if (!isShown(city, construction)) return@synchronized false
        val option = constructionOption(city, construction)
        if (!option.available || expectedGoldCost != null && expectedGoldCost != option.goldCost) return@synchronized false
        val queue = city.cityConstructions.constructionQueue
        if (queuePosition != -1 && (queuePosition !in queue.indices || queue[queuePosition] != name)) return@synchronized false
        // Preserve the native placement path, and do not probe surrounding fog for space.
        if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Gold, option.goldCost!!)) return@synchronized false
        val purchased = city.cityConstructions.purchaseConstruction(construction, queuePosition, automatic = false)
        if (purchased) {
            city.cityStats.update()
            civ.cache.updateCivResources()
            civ.updateStatsForNextTurn()
        }
        purchased
    }

    fun tileOptions(city: City): List<TileOption> {
        if (!owns(city)) return emptyList()
        return frozen(city.tilesInRange.asSequence().filter { it.isVisible(civ) && it.getOwner() == null }
            .sortedWith(compareBy({ it.position.x }, { it.position.y })).map { tileOption(city, it) }.toList())
    }

    private fun tileOption(city: City, tile: Tile): TileOption {
        val reasons = actionReasons().toMutableList()
        if (city.isPuppet) reasons.add("Cannot purchase tiles for a puppet city")
        if (city.isBeingRazed) reasons.add("The city is being razed")
        if (city.isInResistance()) reasons.add("The city is in resistance")
        if (tile.getOwner() != null) reasons.add("The tile is already owned")
        if (tile !in city.tilesInRange) reasons.add("The tile is outside this city's working range")
        if (tile.neighbors.none { it.getCity() === city }) reasons.add("The tile must border this city's territory")
        val cost = city.expansion.getGoldCostOfTile(tile)
        if (!civ.gameInfo.gameParameters.godMode && civ.gold < cost) reasons.add("Not enough gold")
        return TileOption(tile.position.x, tile.position.y, cost, reasons.isEmpty(), frozen(reasons),
            "Currently you have [${civ.gold}] [Gold].\n\nWould you like to purchase [Tile] for [$cost] [${Stat.Gold.character}]?")
    }

    fun tryBuyTile(city: City, tile: Tile, expectedGoldCost: Int? = null): Boolean = synchronized(civ.gameInfo) {
        if (!owns(city) || !PlayerOperations(civ, spectatorMode).canAct() ||
            tile !in city.tilesInRange || !tile.isVisible(civ)) return@synchronized false
        val option = tileOption(city, tile)
        if (!option.available || expectedGoldCost != null && expectedGoldCost != option.goldCost ||
            !city.expansion.canBuyTile(tile)) return@synchronized false
        city.expansion.buyTile(tile)
        city.cityStats.update()
        civ.updateStatsForNextTurn()
        true
    }

    @Readonly
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
