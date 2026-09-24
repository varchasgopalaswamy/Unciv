package com.unciv.logic.battle

import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class DamageExchangeTest {
    private val game = TestGame().apply { makeHexagonalMap(5) }
    private val attackerCiv = game.addCiv()
    private val defenderCiv = game.addCiv()
    private val attackerUnit = game.addUnit("Warrior", attackerCiv, game.getTile(1, 0))
    private val defenderUnit = game.addUnit("Warrior", defenderCiv, game.getTile(0, 0))
    private val attacker = MapUnitCombatant(attackerUnit)
    private val defender = MapUnitCombatant(defenderUnit)

    @Test
    fun `exchange keeps full retaliation and resolves mutual lethality from total uncapped damage`() {
        // HP, incoming rolls, expected damage received. Unequal existing wounds and excess
        // damage distinguish Civ V's total-damage comparison from comparing capped rolls.
        data class Case(val aHp: Int, val dHp: Int, val toA: Int, val toD: Int, val expectedA: Int, val expectedD: Int)
        val cases = listOf(
            Case(100, 100, 20, 30, 20, 30),
            Case(100, 1, 20, 30, 20, 1),
            Case(1, 100, 20, 30, 1, 30),
            Case(1, 12, 20, 30, 1, 11),
            Case(12, 1, 30, 20, 11, 1),
            Case(10, 20, 30, 40, 10, 19), // equal total damage: defender survives
            Case(1, 12, 20, 50, 0, 12),
        )
        for ((aHp, dHp, toA, toD, expectedA, expectedD) in cases) {
            attackerUnit.health = aHp
            defenderUnit.health = dHp
            val before = json().toJson(game.gameInfo)
            val damage = BattleDamage.resolveDamage(attacker, defender, toA, toD)
            assertEquals("Attacker in $aHp/$dHp, rolls $toA/$toD", expectedA, damage.defenderDealt)
            assertEquals("Defender in $aHp/$dHp, rolls $toA/$toD", expectedD, damage.attackerDealt)
            assertEquals(before, json().toJson(game.gameInfo))
        }
    }

    @Test
    fun `a dying attacker still deals its full damage roll`() {
        attackerUnit.health = 1
        val incoming = BattleDamage.calculateDamageToDefender(attacker, defender)

        val result = Battle.attack(attacker, defender)

        assertTrue(attackerUnit.isDestroyed)
        assertFalse(defenderUnit.isDestroyed)
        assertEquals(incoming, result.attackerDealt)
        assertEquals(100 - incoming, defenderUnit.health)
        assertEquals(1, result.defenderDealt)
    }

    @Test
    fun `a dying defender still deals its full retaliation roll`() {
        defenderUnit.health = 1
        val retaliation = BattleDamage.calculateDamageToAttacker(attacker, defender)

        val result = Battle.attack(attacker, defender)

        assertFalse(attackerUnit.isDestroyed)
        assertTrue(defenderUnit.isDestroyed)
        assertEquals(retaliation, result.defenderDealt)
        assertEquals(100 - retaliation, attackerUnit.health)
        assertEquals(1, result.attackerDealt)
        assertEquals(defender.getTile(), attacker.getTile())
    }

    @Test
    fun `equally wounded units leave the defender alive at one HP on a tied roll`() {
        // Turn zero produces the same damage randomness at both locations.
        game.gameInfo.turns = 0
        attackerUnit.health = 10
        defenderUnit.health = 10

        val result = Battle.attack(attacker, defender)

        assertTrue(attackerUnit.isDestroyed)
        assertFalse(defenderUnit.isDestroyed)
        assertEquals(1, defenderUnit.health)
        assertEquals(10, result.defenderDealt)
        assertEquals(9, result.attackerDealt)
    }

    @Test
    fun `capturing a city preserves a one HP melee attacker`() {
        defenderUnit.destroy()
        val city = game.addCity(defenderCiv, game.getTile(0, 0))
        city.health = 5
        attackerUnit.health = 1
        game.gameInfo.currentPlayerCiv = game.addCiv()
        game.gameInfo.currentPlayer = game.gameInfo.currentPlayerCiv.civID
        val cityCombatant = CityCombatant(city)
        val range = BattleDamage.damageRange(attacker, cityCombatant, attacker.getTile())
        assertEquals(0, range.minDamageToAttacker)
        assertEquals(0, range.maxDamageToAttacker)
        assertEquals(4, range.minDamageToDefender)
        assertEquals(4, range.maxDamageToDefender)

        val result = Battle.attack(attacker, cityCombatant)

        assertEquals(1, attackerUnit.health)
        assertFalse(attackerUnit.isDestroyed)
        assertEquals(attackerCiv, city.civ)
        assertEquals(0, result.defenderDealt)
        assertEquals(4, result.attackerDealt)
    }

    @Test
    fun `ranged fire leaves a city at one HP without retaliation or capture`() {
        defenderUnit.destroy()
        val archer = game.addUnit("Archer", attackerCiv, game.getTile(0, 1))
        val city = game.addCity(defenderCiv, game.getTile(0, 0))
        city.health = 5
        archer.health = 1

        val result = Battle.attack(MapUnitCombatant(archer), CityCombatant(city))

        assertEquals(1, archer.health)
        assertEquals(1, city.health)
        assertEquals(defenderCiv, city.civ)
        assertEquals(0, result.defenderDealt)
        assertEquals(4, result.attackerDealt)
    }

    @Test
    fun `a one HP city can still bombard`() {
        val city = game.addCity(attackerCiv, game.getTile(2, 0))
        city.health = 1

        val result = Battle.attack(CityCombatant(city), defender)

        assertTrue(result.attackerDealt > 0)
        assertEquals(0, result.defenderDealt)
        assertEquals(1, city.health)
    }

    @Test
    fun `opposing random extremes include either survivor without promising mutual destruction`() {
        attackerUnit.health = 10
        defenderUnit.health = 10
        val range = BattleDamage.damageRange(attacker, defender, attacker.getTile())
        assertEquals(9, range.minDamageToAttacker)
        assertEquals(10, range.maxDamageToAttacker)
        assertEquals(9, range.minDamageToDefender)
        assertEquals(10, range.maxDamageToDefender)
    }

    @Test
    fun `preview bounds contain independent damage rolls across lethal thresholds`() {
        for (aHp in listOf(1, 5, 12, 20, 30, 60, 100)) {
            for (dHp in listOf(1, 5, 12, 20, 30, 60, 100)) {
                attackerUnit.health = aHp
                defenderUnit.health = dHp
                val before = json().toJson(game.gameInfo)
                val range = BattleDamage.damageRange(attacker, defender, attacker.getTile())
                val results = ArrayList<Battle.DamageDealt>()
                for (aRoll in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    for (dRoll in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                        val result = BattleDamage.calculateDamage(attacker, defender, attacker.getTile(), aRoll, dRoll)
                        assertTrue("$aHp/$dHp: $result outside $range",
                            result.defenderDealt in range.minDamageToAttacker..range.maxDamageToAttacker &&
                            result.attackerDealt in range.minDamageToDefender..range.maxDamageToDefender)
                        assertFalse(result.defenderDealt == aHp && result.attackerDealt == dHp)
                        results.add(result)
                    }
                }
                assertEquals(results.minOf { it.defenderDealt }, range.minDamageToAttacker)
                assertEquals(results.maxOf { it.defenderDealt }, range.maxDamageToAttacker)
                assertEquals(results.minOf { it.attackerDealt }, range.minDamageToDefender)
                assertEquals(results.maxOf { it.attackerDealt }, range.maxDamageToDefender)
                assertEquals(before, json().toJson(game.gameInfo))
            }
        }
    }
}
