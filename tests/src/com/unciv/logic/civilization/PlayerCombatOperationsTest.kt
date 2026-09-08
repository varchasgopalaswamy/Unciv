package com.unciv.logic.civilization

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerCombatOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(8) }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val enemy = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val warrior = game.addUnit("Warrior", player, game.getTile(0, 0))
    private val defender = game.addUnit("Warrior", enemy, game.getTile(2, 0))
    private val operations = PlayerCombatOperations(player)

    init {
        game.addUnit("Settler", player, game.getTile(-6, 0))
        game.addUnit("Settler", enemy, game.getTile(6, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(enemy)
        player.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(player, true)
        player.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `melee preview uses UI damage range and the execution moves damages and awards experience`() {
        val original = json().toJson(game.gameInfo)
        val offer = operations.attacks(warrior).first { it.attackFrom === game.getTile(1, 0) }
        assertEquals(listOf(game.getTile(1, 0)), offer.path)
        assertEquals(1f, offer.movementCost, 0.001f)
        assertEquals(BattleDamage.calculateDamageToDefender(MapUnitCombatant(warrior),
            MapUnitCombatant(defender), offer.attackFrom, 0f), offer.minDamageToDefender)
        assertEquals(BattleDamage.calculateDamageToDefender(MapUnitCombatant(warrior),
            MapUnitCombatant(defender), offer.attackFrom, 1f), offer.maxDamageToDefender)
        assertEquals(original, json().toJson(game.gameInfo))

        val result = operations.tryAttack(warrior, offer.target, offer.attackFrom)!!
        assertTrue(result.attacked)
        assertTrue(result.damageToAttacker > 0)
        assertTrue(result.damageToDefender > 0)
        assertEquals(100 - result.damageToAttacker, warrior.health)
        assertEquals(100 - result.damageToDefender, defender.health)
        assertEquals(offer.attackFrom, warrior.currentTile)
        assertEquals(1, warrior.attacksThisTurn)
        assertTrue(warrior.promotions.XP > 0)
        assertTrue(defender.promotions.XP > 0)
        assertNull(operations.tryAttack(warrior, offer.target, offer.attackFrom))
    }

    @Test
    fun `attacks reject foreign units inactive players spectators and stale destinations without changes`() {
        val offer = operations.attacks(warrior).first()
        val before = json().toJson(game.gameInfo)
        assertNull(operations.tryAttack(defender, warrior.currentTile, defender.currentTile))
        assertNull(PlayerCombatOperations(enemy).tryAttack(defender, warrior.currentTile, defender.currentTile))
        assertNull(PlayerCombatOperations(player, true).tryAttack(warrior, offer.target, offer.attackFrom))
        assertNull(operations.tryAttack(warrior, game.getTile(7, 0), offer.attackFrom))
        assertNull(operations.tryAttack(warrior, offer.target, game.getTile(-7, 0)))
        assertEquals(before, json().toJson(game.gameInfo))
        warrior.currentMovement = 0f
        assertTrue(operations.attacks(warrior).isEmpty())
        assertNull(operations.tryAttack(warrior, offer.target, offer.attackFrom))
    }

    @Test
    fun `combat never declares war implicitly`() {
        val offer = operations.attacks(warrior).first()
        player.getDiplomacyManager(enemy)!!.diplomaticStatus = DiplomaticStatus.Peace
        enemy.getDiplomacyManager(player)!!.diplomaticStatus = DiplomaticStatus.Peace
        val before = json().toJson(game.gameInfo)
        assertTrue(operations.attacks(warrior).isEmpty())
        assertNull(operations.tryAttack(warrior, offer.target, offer.attackFrom))
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test
    fun `ranged units damage without retaliation and cannot repeat the same attack`() {
        val archer = game.addUnit("Archer", player, game.getTile(0, -1))
        val offer = operations.attacks(archer).first { it.attackFrom === archer.currentTile }
        assertEquals(0, offer.maxDamageToAttacker)
        val result = operations.tryAttack(archer, defender.currentTile, archer.currentTile)!!
        assertTrue(result.attacked)
        assertEquals(0, result.damageToAttacker)
        assertEquals(100, archer.health)
        assertTrue(defender.health < 100)
        assertNull(operations.tryAttack(archer, defender.currentTile, archer.currentTile))
    }

    @Test
    fun `city bombard uses visible targets once per turn and never attacks another city`() {
        val city = game.addCity(player, game.getTile(0, 0))
        game.addCity(enemy, game.getTile(0, 2))
        player.viewableTiles = game.tileMap.values.toSet()
        assertFalse(operations.attacks(city).any { it.target.isCityCenter() })
        val result = operations.tryAttack(city, defender.currentTile)!!
        assertTrue(result.attacked)
        assertEquals(0, result.damageToAttacker)
        assertTrue(city.attackedThisTurn)
        assertTrue(defender.health < 100)
        assertNull(operations.tryAttack(city, defender.currentTile))
        assertTrue(operations.attacks(city).isEmpty())
    }

    @Test
    fun `siege setup consumes movement and the attack is legal only with movement left`() {
        val catapult = game.addUnit("Catapult", player, game.getTile(0, -1))
        catapult.currentMovement = 1f
        assertTrue(operations.attacks(catapult).isEmpty())
        catapult.currentMovement = 2f
        assertTrue(operations.tryAttack(catapult, defender.currentTile, catapult.currentTile)!!.attacked)
        assertTrue(catapult.isSetUpForSiege())
        assertTrue(defender.health < 100)
    }

    @Test
    fun `hidden and invisible targets are not offered or accepted`() {
        val offer = operations.attacks(warrior).first()
        player.viewableTiles = player.viewableTiles - defender.currentTile
        assertTrue(operations.attacks(warrior).isEmpty())
        assertNull(operations.tryAttack(warrior, offer.target, offer.attackFrom))
        val tile = defender.currentTile
        defender.destroy()
        val invisible = game.addDefaultMeleeUnitWithUniques(enemy, tile, "Invisible to others")
        player.viewableTiles = game.tileMap.values.toSet()
        assertFalse(invisible.isVisibleTo(player))
        assertTrue(operations.attacks(warrior).isEmpty())
        player.viewableInvisibleUnitsTiles = setOf(tile)
        assertTrue(operations.attacks(warrior).isNotEmpty())
    }

    @Test
    fun `planning does not reveal hidden units zones of control or pathfinder settings`() {
        player.viewableTiles = setOf(warrior.currentTile, defender.currentTile)
        val before = operations.attacks(warrior)
        val hidden = game.addUnit("Warrior", enemy, game.getTile(1, 0))
        player.viewableTiles = setOf(warrior.currentTile, defender.currentTile)
        assertFalse(hidden.isVisibleTo(player))
        assertEquals(before, operations.attacks(warrior))
        UncivGame.Current.settings.useAStarPathfinding = !UncivGame.Current.settings.useAStarPathfinding
        assertEquals(before, operations.attacks(warrior))
        val result = operations.tryAttack(warrior, defender.currentTile, game.getTile(1, 0))!!
        assertFalse(result.attacked)
        assertEquals(100, hidden.health)
        assertEquals(100, defender.health)
        assertEquals(game.getTile(0, 0), warrior.currentTile)
    }

    @Test
    fun `unexplored terrain does not affect ranged line of sight offers`() {
        val archer = game.addUnit("Archer", player, game.getTile(0, -1))
        val hidden = game.getTile(1, 0)
        hidden.setExplored(player, false)
        player.viewableTiles = setOf(archer.currentTile, warrior.currentTile, defender.currentTile)
        val before = operations.attacks(archer)
        assertTrue(before.isNotEmpty())
        hidden.baseTerrain = "Mountain"
        hidden.setTerrainTransients()
        assertEquals(before, operations.attacks(archer))
    }

    @Test
    fun `unexplored terrain can stop a planned attack without damaging the target`() {
        defender.removeFromTile()
        defender.putInTile(game.getTile(3, 0))
        warrior.currentMovement = 3f
        val hidden = game.getTile(1, 0)
        hidden.setExplored(player, false)
        player.viewableTiles = setOf(warrior.currentTile, defender.currentTile)
        val offer = operations.attacks(warrior).first { it.attackFrom === game.getTile(2, 0) }
        assertTrue(hidden in offer.path)
        val before = operations.attacks(warrior)
        hidden.baseTerrain = "Mountain"
        hidden.setTerrainTransients()
        assertEquals(before, operations.attacks(warrior))
        assertFalse(operations.tryAttack(warrior, offer.target, offer.attackFrom)!!.attacked)
        assertEquals(100, defender.health)
        assertEquals(0, warrior.attacksThisTurn)
    }

    @Test
    fun `melee destruction and civilian capture return actual outcomes`() {
        defender.health = 1
        val offer = operations.attacks(warrior).first()
        val result = operations.tryAttack(warrior, offer.target, offer.attackFrom)!!
        assertTrue(result.defenderDestroyed)
        assertTrue(defender.isDestroyed)
        assertEquals(offer.target, warrior.currentTile)

        val worker = game.addUnit("Worker", enemy, game.getTile(3, 0))
        warrior.attacksThisTurn = 0
        warrior.currentMovement = 2f
        player.viewableTiles = game.tileMap.values.toSet()
        val capture = operations.tryAttack(warrior, worker.currentTile, warrior.currentTile)!!
        assertTrue(capture.attacked)
        assertTrue(capture.defenderCaptured)
        assertEquals(player, worker.civ)
        assertFalse(worker.isDestroyed)
    }

    @Test
    fun `conquering a city reports capture and leaves its disposition to the player`() {
        val city = game.addCity(enemy, defender.currentTile)
        city.health = 1
        val offer = operations.attacks(warrior).first()
        val result = operations.tryAttack(warrior, city.getCenterTile(), offer.attackFrom)!!
        assertTrue(result.attacked)
        assertTrue(result.defenderCaptured)
        assertTrue(city.hasJustBeenConquered)
        assertEquals(enemy, city.civ)
        assertTrue(player.popupAlerts.any { it.type.name == "CityConquered" && it.value == city.id })
    }

    @Test
    fun `a captured settler converted to a worker is not reported destroyed`() {
        defender.destroy()
        val settler = game.addUnit("Settler", enemy, game.getTile(1, 0))
        settler.health = 65
        val result = operations.tryAttack(warrior, settler.currentTile, warrior.currentTile)!!
        val worker = player.units.getUnitById(settler.id)!!
        assertEquals("Worker", worker.name)
        assertTrue(result.defenderCaptured)
        assertFalse(result.defenderDestroyed)
        assertEquals(worker.health, result.defenderHealth)
    }

    @Test
    fun `preview damage is clamped to the health shown by the battle table`() {
        defender.health = 1
        warrior.health = 5
        val preview = operations.attacks(warrior).first()
        assertEquals(1, preview.minDamageToDefender)
        assertEquals(1, preview.maxDamageToDefender)
        assertTrue(preview.minDamageToAttacker in 0..warrior.health)
        assertTrue(preview.maxDamageToAttacker in preview.minDamageToAttacker..warrior.health)
        defender.destroy()
        game.addUnit("Worker", enemy, game.getTile(1, 0))
        val capture = operations.attacks(warrior).first()
        assertEquals(0, capture.minDamageToDefender)
        assertEquals(0, capture.maxDamageToDefender)
        assertEquals(0, capture.maxDamageToAttacker)
    }

    @Test
    fun `unsupported sea air nuclear and civilian units are explicit and have no attack offers`() {
        val city = game.addCity(player, game.getTile(-2, 0))
        for (name in listOf("Trireme", "Fighter", "Atomic Bomb", "Worker")) {
            val unit = game.addUnit(name, player, city.getCenterTile())
            assertFalse(name, operations.supportsAttack(unit))
            assertTrue(name, operations.attacks(unit).isEmpty())
            assertNull(operations.tryAttack(unit, defender.currentTile, unit.currentTile))
        }
    }

    @Test
    fun `withdrawal does not disclose a hidden retreat destination or later health`() {
        defender.destroy()
        val withdrawing = game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(1, 0),
            UniqueType.WithdrawsBeforeMeleeCombat.text)
        player.viewableTiles = setOf(warrior.currentTile, withdrawing.currentTile)
        val result = operations.tryAttack(warrior, withdrawing.currentTile, warrior.currentTile)!!
        assertTrue(result.attacked)
        assertEquals(0, result.damageToDefender)
        assertEquals(0, result.damageToAttacker)
        assertFalse(result.defenderDestroyed)
        assertFalse(result.defenderCaptured)
        assertNotEquals(game.getTile(1, 0), withdrawing.currentTile)
        assertNull(result.defenderHealth)
    }
}
