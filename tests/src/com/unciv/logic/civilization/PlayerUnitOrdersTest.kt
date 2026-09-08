package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerUnitOrders.Order
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerUnitOrdersTest {
    private val game = TestGame().apply { makeHexagonalMap(8, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val warrior = game.addUnit("Warrior", player, game.getTile(0, 0))
    private val worker = game.addUnit("Worker", player, game.getTile(1, 0))
    private val foreign = game.addUnit("Warrior", other, game.getTile(6, 0))
    private val operations = PlayerUnitOrders(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
    }

    @Test
    fun `orders reject foreign spectator inactive and destroyed units without mutation`() {
        val before = json().toJson(game.gameInfo)
        for (order in Order.entries) {
            assertFalse(operations.tryOrder(foreign, order))
            assertFalse(PlayerUnitOrders(other).tryOrder(foreign, order))
            assertFalse(PlayerUnitOrders(player, true).tryOrder(warrior, order))
        }
        assertNull(operations.status(foreign))
        assertTrue(operations.orders(foreign).isEmpty())
        assertNull(operations.promotions(foreign))
        assertNull(PlayerUnitOrders(player, true).status(warrior))
        assertEquals(before, json().toJson(game.gameInfo))
        warrior.destroy()
        assertNull(operations.status(warrior))
        assertFalse(operations.tryOrder(warrior, Order.FORTIFY))
    }

    @Test
    fun `fortification and wake preserve movement and build defense on end turn`() {
        val movement = warrior.currentMovement
        assertTrue(operations.tryOrder(warrior, Order.FORTIFY))
        assertEquals("Fortify", operations.status(warrior)!!.action)
        assertFalse(operations.status(warrior)!!.idle)
        assertFalse(operations.tryOrder(warrior, Order.FORTIFY))
        assertEquals(movement, warrior.currentMovement, 0f)
        UnitTurnManager(warrior).endTurn()
        UnitTurnManager(warrior).startTurn()
        assertEquals(1, operations.status(warrior)!!.fortificationTurns)
        assertTrue(operations.tryOrder(warrior, Order.WAKE))
        assertNull(warrior.action)
        assertTrue(warrior.due)
        assertTrue(operations.status(warrior)!!.idle)
        assertFalse(operations.tryOrder(warrior, Order.WAKE))
    }

    @Test
    fun `healing orders use normal end turn healing and wake when healed`() {
        warrior.health = 99
        assertTrue(operations.status(warrior)!!.healingPerTurn > 0)
        assertTrue(operations.tryOrder(warrior, Order.FORTIFY_UNTIL_HEALED))
        UnitTurnManager(warrior).endTurn()
        assertEquals(100, warrior.health)
        UnitTurnManager(warrior).startTurn()
        assertNull(warrior.action)
        assertFalse(operations.tryOrder(warrior, Order.FORTIFY_UNTIL_HEALED))
        worker.health = 99
        assertTrue(operations.tryOrder(worker, Order.SLEEP_UNTIL_HEALED))
        UnitTurnManager(worker).endTurn()
        UnitTurnManager(worker).startTurn()
        assertEquals(100, worker.health)
        assertNull(worker.action)
    }

    @Test
    fun `skip only changes due status and resets on the next turn`() {
        val movement = warrior.currentMovement
        assertTrue(operations.tryOrder(warrior, Order.SKIP))
        assertFalse(warrior.due)
        assertTrue(warrior.isIdle())
        assertEquals(movement, warrior.currentMovement, 0f)
        assertFalse(operations.tryOrder(warrior, Order.SKIP))
        UnitTurnManager(warrior).startTurn()
        assertTrue(warrior.due)
        assertTrue(operations.tryOrder(warrior, Order.SKIP))
        assertTrue(operations.tryOrder(warrior, Order.WAKE))
        assertTrue(warrior.due)
    }

    @Test
    fun `sleep and fortify restrictions follow unit type movement and current worker job`() {
        assertFalse(operations.tryOrder(worker, Order.FORTIFY))
        assertFalse(operations.tryOrder(warrior, Order.SLEEP))
        assertTrue(operations.tryOrder(worker, Order.SLEEP))
        assertFalse(operations.tryOrder(worker, Order.SLEEP))
        assertTrue(operations.tryOrder(worker, Order.WAKE))
        worker.currentTile.startWorkingOnImprovement(game.ruleset.tileImprovements.getValue("Farm"), player, worker)
        assertFalse(operations.tryOrder(worker, Order.SLEEP))
        worker.currentTile.stopWorkingOnImprovement()
        warrior.currentMovement = 0f
        assertTrue(operations.orders(warrior).all { !it.available })
        assertTrue(operations.orders(warrior).all { "No movement remaining" in it.unavailableReasons })
        warrior.currentMovement = 1f
        warrior.health = 50
        assertEquals(0, operations.status(warrior)!!.healingPerTurn)
        // Issuing the order is legal after moving, but it does not grant immediate healing.
        assertTrue(operations.tryOrder(warrior, Order.FORTIFY_UNTIL_HEALED))
        UnitTurnManager(warrior).endTurn()
        assertEquals(50, warrior.health)
    }

    @Test
    fun `desktop tactical actions execute the validated operations`() {
        UnitActions.getUnitActions(warrior, UnitActionType.Fortify).first().action!!.invoke()
        assertEquals("Fortify", warrior.action)
        UnitActions.getUnitActions(warrior, UnitActionType.Wake).first().action!!.invoke()
        assertNull(warrior.action)
        UnitActions.getUnitActions(worker, UnitActionType.Sleep).first().action!!.invoke()
        assertEquals("Sleep", worker.action)
        UncivGame.Current.settings.autoUnitCycle = true
        UnitActions.getUnitActions(worker, UnitActionType.Skip).first().action!!.invoke()
        assertFalse(worker.due)
        UnitActions.getUnitActions(worker, UnitActionType.Skip).first().action!!.invoke()
        assertTrue(worker.due)
        assertEquals("Sleep", worker.action)
        val staleAction = UnitActions.getUnitActions(worker, UnitActionType.Wake).first().action!!
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        staleAction.invoke()
        assertEquals("Sleep", worker.action)
    }

    @Test
    fun `promotion choices expose descriptions prerequisites costs and enforce them`() {
        warrior.promotions.XP = 30
        val before = json().toJson(game.gameInfo)
        val choices = operations.promotions(warrior)!!
        assertEquals(30, choices.experience)
        assertEquals(10, choices.experienceForNextPromotion)
        val first = choices.choices.single { it.name == "Shock I" }
        assertTrue(first.description.isNotBlank())
        val dependent = choices.choices.first { first.name in it.prerequisites }
        assertFalse(dependent.available)
        assertFalse(operations.tryPromote(warrior, dependent.name))
        assertFalse(operations.tryPromote(warrior, "Missing promotion"))
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(operations.tryPromote(warrior, first.name))
        assertEquals(20, warrior.promotions.XP)
        assertEquals(1, warrior.promotions.numberOfPromotions)
        assertFalse(operations.tryPromote(warrior, first.name))
        assertTrue(operations.tryPromote(warrior, dependent.name))
        assertEquals(0, warrior.promotions.XP)
        assertEquals(2, warrior.promotions.numberOfPromotions)
        assertFalse(operations.canPromote(warrior))
        assertEquals(2f, warrior.currentMovement, 0f)
        assertThrows(UnsupportedOperationException::class.java) { (choices.choices as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (dependent.prerequisites as MutableList<*>).clear() }
        // The earlier observation remains a detached snapshot after both mutations.
        assertEquals(30, choices.experience)
        assertFalse(first.acquired)
    }

    @Test
    fun `promotions reject foreign actors spent movement and attacks without spending experience`() {
        warrior.promotions.XP = 30
        foreign.promotions.XP = 30
        val choice = operations.promotions(warrior)!!.choices.first { it.available }.name
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.tryPromote(foreign, choice))
        assertFalse(PlayerUnitOrders(other).tryPromote(foreign, choice))
        assertFalse(PlayerUnitOrders(player, true).tryPromote(warrior, choice))
        assertEquals(before, json().toJson(game.gameInfo))
        warrior.currentMovement = 0f
        assertFalse(operations.canPromote(warrior))
        assertFalse(operations.tryPromote(warrior, choice))
        warrior.currentMovement = 1f
        warrior.attacksThisTurn = 1
        assertFalse(operations.tryPromote(warrior, choice))
        assertTrue(operations.promotions(warrior)!!.choices.all { !it.available })
        assertEquals(30, warrior.promotions.XP)
        warrior.attacksThisTurn = 0
        assertTrue(operations.tryPromote(warrior, choice))
    }

    @Test
    fun `free promotions do not spend experience and conditional promotions stay unavailable`() {
        val free = game.createUnitPromotion(UniqueType.FreePromotion.text).apply {
            unitTypes = listOf(warrior.type.name)
        }
        val unavailable = game.createUnitPromotion(UniqueType.Unavailable.text).apply {
            unitTypes = listOf(warrior.type.name)
        }
        assertTrue(operations.canPromote(warrior))
        assertFalse(operations.tryPromote(warrior, unavailable.name))
        assertTrue(operations.tryPromote(warrior, free.name))
        assertEquals(0, warrior.promotions.XP)
        assertEquals(0, warrior.promotions.numberOfPromotions)
    }

    @Test
    fun `orders and purchased promotions survive a complete save reload`() {
        warrior.health = 50
        warrior.promotions.XP = 30
        val promotion = "Shock I"
        assertTrue(operations.tryPromote(warrior, promotion))
        assertTrue(operations.tryOrder(warrior, Order.FORTIFY_UNTIL_HEALED))
        assertTrue(operations.tryOrder(worker, Order.SLEEP))
        assertTrue(operations.tryOrder(worker, Order.SKIP))
        val restored = json().fromJson(GameInfo::class.java, json().toJson(game.gameInfo))
        restored.setTransients()
        val restoredPlayer = restored.getCivilization(player.civID)
        val restoredOperations = PlayerUnitOrders(restoredPlayer)
        val restoredWarrior = restoredPlayer.units.getCivUnits().single { it.name == "Warrior" }
        val restoredWorker = restoredPlayer.units.getCivUnits().single { it.name == "Worker" }
        assertEquals(operations.status(warrior), restoredOperations.status(restoredWarrior))
        assertEquals(operations.status(worker), restoredOperations.status(restoredWorker))
        assertEquals(20, restoredWarrior.promotions.XP)
        assertTrue(promotion in restoredWarrior.promotions.promotions)
        assertFalse(restoredOperations.tryPromote(restoredWarrior, promotion))
        assertTrue(restoredOperations.tryOrder(restoredWarrior, Order.WAKE))
    }
}
