package com.unciv.logic.civilization

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.Promotion
import java.util.Collections

/** Explicit tactical orders and promotion choices for an ordinary human player.
 *
 * Orders retain the same movement and end-of-turn healing behavior as the desktop
 * controls. In particular, skipping only removes a unit from this turn's due queue;
 * it does not spend movement. Persistent sleep and fortification live on the unit
 * and therefore survive saving and loading without a separate order queue.
 */
class PlayerUnitOrders(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    enum class Order { FORTIFY, FORTIFY_UNTIL_HEALED, SLEEP, SLEEP_UNTIL_HEALED, SKIP, WAKE }

    data class Status(
        val action: String?,
        val due: Boolean,
        val idle: Boolean,
        val fortificationTurns: Int,
        /** Expected healing at the end of this turn, before subsequent actions or damage. */
        val healingPerTurn: Int,
    )

    data class OrderOption(val order: Order, val available: Boolean, val unavailableReasons: List<String>)

    data class PromotionOption(
        val name: String,
        val description: String,
        /** At least one prerequisite is required, matching the promotion picker. */
        val prerequisites: List<String>,
        val acquired: Boolean,
        val experienceCost: Int,
        val available: Boolean,
        val unavailableReasons: List<String>,
    )

    data class PromotionStatus(
        val experience: Int,
        val experienceForNextPromotion: Int,
        val earnedPromotions: Int,
        val choices: List<PromotionOption>,
    )

    private fun canInspect(unit: MapUnit): Boolean = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ } && PlayerUnitOperations(civ).owns(unit)

    fun status(unit: MapUnit): Status? {
        if (!canInspect(unit)) return null
        val canHealThisTurn = (!unit.hasUnitMovedThisTurn() && unit.attacksThisTurn == 0) ||
            unit.hasUnique(UniqueType.HealsEvenAfterAction)
        return Status(unit.action, unit.due, unit.isIdle(), unit.getFortificationTurns(),
            if (canHealThisTurn) unit.getHealAmountForCurrentTile().coerceAtMost(100 - unit.health) else 0)
    }

    fun orders(unit: MapUnit): List<OrderOption> {
        if (!canInspect(unit)) return emptyList()
        return immutable(Order.entries.map { order ->
            val reasons = orderReasons(unit, order)
            OrderOption(order, reasons.isEmpty(), immutable(reasons))
        })
    }

    fun canOrder(unit: MapUnit, order: Order): Boolean = canInspect(unit) && orderReasons(unit, order).isEmpty()

    private fun orderReasons(unit: MapUnit, order: Order): List<String> = buildList {
        if (!PlayerOperations(civ, spectatorMode).canAct()) add("This player cannot act now")
        if (!unit.hasMovement()) add("No movement remaining")
        when (order) {
            Order.FORTIFY, Order.FORTIFY_UNTIL_HEALED -> {
                if (unit.isFortified()) add("Already fortified; wake the unit before changing its order")
                else if (!unit.canFortify()) add("This unit cannot fortify here")
            }
            Order.SLEEP, Order.SLEEP_UNTIL_HEALED -> {
                if (unit.isFortified() || unit.canFortify() || unit.isGuarding())
                    add("Use fortification or wake the unit before sleeping")
                val tile = unit.currentTile
                if (tile.hasImprovementInProgress() && unit.canBuildImprovement(tile.getTileImprovementInProgress()!!))
                    add("Stop the improvement job before sleeping")
                if (order == Order.SLEEP && unit.isSleeping() && !unit.isSleepingUntilHealed() ||
                    order == Order.SLEEP_UNTIL_HEALED && unit.isSleepingUntilHealed())
                    add("This sleep order is already active")
            }
            Order.SKIP -> if (!unit.due) add("Already skipped this turn")
            Order.WAKE -> if (!unit.isFortified() && !unit.isSleeping() && unit.due)
                add("The unit is already awake and due")
        }
        if (order == Order.FORTIFY_UNTIL_HEALED || order == Order.SLEEP_UNTIL_HEALED) {
            if (unit.health >= 100) add("The unit is already at full health")
            else if (!unit.canHealInCurrentTile()) add("The unit cannot heal on this tile")
        }
    }

    fun tryOrder(unit: MapUnit, order: Order): Boolean = synchronized(civ.gameInfo) {
        if (!canOrder(unit, order)) return@synchronized false
        when (order) {
            Order.FORTIFY -> unit.fortify()
            Order.FORTIFY_UNTIL_HEALED -> unit.fortifyUntilHealed()
            Order.SLEEP -> unit.action = UnitActionType.Sleep.value
            Order.SLEEP_UNTIL_HEALED -> unit.action = UnitActionType.SleepUntilHealed.value
            Order.SKIP -> unit.due = false
            Order.WAKE -> {
                // Undoing Skip must not cancel an unrelated worker, movement or setup order.
                if (unit.isFortified() || unit.isSleeping()) unit.action = null
                unit.due = true
            }
        }
        true
    }

    /** The desktop Skip button is a due-only toggle, including for sleeping units. */
    fun trySetSkipped(unit: MapUnit, skipped: Boolean): Boolean = synchronized(civ.gameInfo) {
        if (!canInspect(unit) || !PlayerOperations(civ, spectatorMode).canAct() || !unit.hasMovement() || unit.due == !skipped)
            return@synchronized false
        unit.due = !skipped
        true
    }

    fun promotions(unit: MapUnit): PromotionStatus? {
        if (!canInspect(unit)) return null
        val relevant = civ.gameInfo.ruleset.unitPromotions.values.filter {
            unit.type.name in it.unitTypes || it.name in unit.promotions.promotions
        }
        val choices = relevant.map { promotion ->
            val reasons = promotionReasons(unit, promotion)
            PromotionOption(promotion.name, promotion.getDescription(relevant), immutable(promotion.prerequisites),
                promotion.name in unit.promotions.promotions, promotionCost(unit, promotion),
                reasons.isEmpty(), immutable(reasons))
        }
        return PromotionStatus(unit.promotions.XP, unit.promotions.xpForNextPromotion(),
            unit.promotions.numberOfPromotions, immutable(choices))
    }

    fun canPromote(unit: MapUnit): Boolean = canInspect(unit) &&
        PlayerOperations(civ, spectatorMode).canAct() && unit.hasMovement() && unit.attacksThisTurn == 0 &&
        unit.promotions.getAvailablePromotions().any { unit.promotions.XP >= promotionCost(unit, it) }

    private fun promotionCost(unit: MapUnit, promotion: Promotion): Int =
        if (promotion.hasUnique(UniqueType.FreePromotion)) 0 else unit.promotions.xpForNextPromotion()

    private fun promotionReasons(unit: MapUnit, promotion: Promotion): List<String> = buildList {
        if (!PlayerOperations(civ, spectatorMode).canAct()) add("This player cannot act now")
        if (!unit.hasMovement()) add("No movement remaining")
        if (unit.attacksThisTurn != 0) add("The unit has already attacked this turn")
        if (promotion.name in unit.promotions.promotions) add("This promotion is already acquired")
        if (unit.type.name !in promotion.unitTypes) add("This promotion is unavailable for this unit type")
        if (promotion.prerequisites.isNotEmpty() && promotion.prerequisites.none { it in unit.promotions.promotions })
            add("Requires one of: ${promotion.prerequisites.joinToString()}")
        if (unit.promotions.getAvailablePromotions().none { it === promotion } &&
            promotion.name !in unit.promotions.promotions &&
            unit.type.name in promotion.unitTypes &&
            (promotion.prerequisites.isEmpty() || promotion.prerequisites.any { it in unit.promotions.promotions }))
            add("Promotion conditions are not satisfied")
        if (unit.promotions.XP < promotionCost(unit, promotion)) add("Not enough experience")
    }

    fun tryPromote(unit: MapUnit, promotionName: String): Boolean = synchronized(civ.gameInfo) {
        if (!canInspect(unit)) return@synchronized false
        val promotion = civ.gameInfo.ruleset.unitPromotions[promotionName] ?: return@synchronized false
        if (promotionReasons(unit, promotion).isNotEmpty()) return@synchronized false
        unit.promotions.addPromotion(promotionName)
        true
    }

    private fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
