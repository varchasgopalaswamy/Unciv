package com.unciv.logic.civilization

import com.unciv.logic.battle.AttackResolution
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/**
 * Player-controlled land, naval and air combat and city bombardment. Queries show visible enemies and speculative routes;
 * execution rechecks the route, target identity, visibility and range after moving.
 * Air sweeps, nuclear attacks and declarations of war use separate operations.
 */
class PlayerCombatOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    data class AttackPreview(
        val attacker: ICombatant,
        val defender: ICombatant,
        val attackFrom: Tile,
        val target: Tile,
        val path: List<Tile>,
        val movementCost: Float,
        val attackerStrength: Float,
        val defenderStrength: Float,
        val attackerModifiers: Map<String, Int>,
        val defenderModifiers: Map<String, Int>,
        val minDamageToAttacker: Int,
        val maxDamageToAttacker: Int,
        val minDamageToDefender: Int,
        val maxDamageToDefender: Int,
    )

    /** An accepted route may stop before attacking when it discovers an obstacle. */
    data class AttackResult(
        val attacked: Boolean,
        val damageToAttacker: Int,
        val damageToDefender: Int,
        val attackerHealth: Int,
        val defenderHealth: Int?,
        val attackerDestroyed: Boolean,
        val defenderDestroyed: Boolean,
        val defenderCaptured: Boolean,
        val resolution: AttackResolution? = null,
    )

    @Readonly
    fun supportsAttack(unit: MapUnit): Boolean = PlayerUnitOperations(civ).owns(unit) &&
        !unit.isCivilian() && !unit.isNuclearWeapon() &&
        !unit.isPreparingParadrop() && !unit.isPreparingAirSweep()

    @Readonly @Suppress("purity") // Detached planning paths and result collections only
    fun attacks(unit: MapUnit): List<AttackPreview> {
        if (!PlayerOperations(civ, spectatorMode).canAct() || !supportsAttack(unit) || !unit.canAttack())
            return emptyList()
        val distances = unit.movement.getMovementToTilesAtPosition(unit.currentTile.position,
            if (unit.baseUnit.isAirUnit()) 0f else unit.currentMovement, forPlanning = true)
        val attacker = MapUnitCombatant(unit)
        return TargetHelper.getAttackableEnemies(unit, distances, forPlanning = true).mapNotNull { offer ->
            // A damage preview uses terrain at the origin (rivers, coast, etc.). Move to an
            // unexplored origin first instead of exposing its terrain through those modifiers.
            if (!offer.tileToAttackFrom.isExplored(civ)) return@mapNotNull null
            val defender = offer.combatant ?: return@mapNotNull null
            val path = distances.getPathToTile(offer.tileToAttackFrom)
            // Walking through a civilian captures it before the intended attack.
            if (offer.tileToAttack in path) return@mapNotNull null
            preview(attacker, defender, offer.tileToAttackFrom, path,
                distances.getValue(offer.tileToAttackFrom).totalMovement)
        }
    }

    @Readonly
    fun attacks(city: City): List<AttackPreview> {
        if (!PlayerOperations(civ, spectatorMode).canAct() || city.civ !== civ ||
            civ.cities.none { it === city } || !city.canBombard()) return emptyList()
        val attacker = CityCombatant(city)
        return TargetHelper.getBombardableTiles(city).mapNotNull { tile ->
            val defender = Battle.getMapCombatantOfTile(tile) ?: return@mapNotNull null
            if (defender is CityCombatant) return@mapNotNull null
            preview(attacker, defender, city.getCenterTile(), emptyList(), 0f)
        }.toList()
    }

    fun tryAttack(unit: MapUnit, target: Tile, attackFrom: Tile): AttackResult? = synchronized(civ.gameInfo) {
        val offer = attacks(unit).firstOrNull { it.target === target && it.attackFrom === attackFrom }
            ?: return@synchronized null
        val defender = offer.defender
        unit.action = unit.action.takeIf { it == UnitActionType.SetUp.value }
        if (!unit.baseUnit.isAirUnit()) unit.movement.moveToTile(attackFrom, plannedPath = offer.path)
        if (unit.isDestroyed || unit.currentTile !== attackFrom)
            return@synchronized result(offer.attacker, defender, false, Battle.DamageDealt.None)

        if (unit.hasUnique(UniqueType.MustSetUp) && !unit.isSetUpForSiege() && unit.hasMovement()) {
            unit.action = UnitActionType.SetUp.value
            unit.useMovementPoints(1f)
        }

        val sameTarget = sameCombatant(defender, Battle.getMapCombatantOfTile(target))
        val stillAttackable = unit.canAttack() && sameTarget &&
            TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), stayOnTile = true)
                .any { it.tileToAttack === target }
        if (!stillAttackable) return@synchronized result(offer.attacker, defender, false, Battle.DamageDealt.None)
        val outcome = Battle.attackWithResult(offer.attacker, defender)
        result(offer.attacker, defender, true, outcome.damage).copy(resolution = outcome.resolution)
    }

    fun tryAttack(city: City, target: Tile): AttackResult? = synchronized(civ.gameInfo) {
        val offer = attacks(city).firstOrNull { it.target === target } ?: return@synchronized null
        val outcome = Battle.attackWithResult(offer.attacker, offer.defender)
        result(offer.attacker, offer.defender, true, outcome.damage).copy(resolution = outcome.resolution)
    }

    @Readonly
    private fun sameCombatant(first: ICombatant, second: ICombatant?): Boolean = when (first) {
        is MapUnitCombatant -> second is MapUnitCombatant && first.unit === second.unit
        is CityCombatant -> second is CityCombatant && first.city === second.city
        else -> false
    }

    @Readonly
    private fun result(attacker: ICombatant, defender: ICombatant, attacked: Boolean,
                       damage: Battle.DamageDealt): AttackResult {
        val defenderUnit = (defender as? MapUnitCombatant)?.unit
        // A captured Settler becomes a Worker with the same ID. The original MapUnit is
        // destroyed as an implementation detail, not a combat casualty.
        val capturedUnit = defenderUnit?.takeIf { it.civ === civ }?.let { civ.units.getUnitById(it.id) }
        val captured = attacked && (capturedUnit != null ||
            (defender as? CityCombatant)?.city?.hasJustBeenConquered == true)
        val defenderVisible = if (defenderUnit != null) defenderUnit.isVisibleTo(civ)
            else defender.getTile().isVisible(civ)
        return AttackResult(attacked, damage.defenderDealt, damage.attackerDealt, attacker.getHealth(),
            capturedUnit?.health ?: defender.getHealth().takeIf { defenderVisible },
            (attacker as? MapUnitCombatant)?.unit?.isDestroyed == true,
            attacked && defenderUnit?.isDestroyed == true && !captured,
            captured)
    }

    @Readonly
    private fun preview(attacker: ICombatant, defender: ICombatant, origin: Tile,
                        path: List<Tile>, cost: Float): AttackPreview {
        // Share the damage exchange with execution and BattleTable, while ranging over
        // both rolls instead of revealing this attack's deterministic random values.
        val damage = BattleDamage.damageRange(attacker, defender, origin)
        var minDamageToDefender = damage.minDamageToDefender
        var maxDamageToDefender = damage.maxDamageToDefender
        if (attacker is MapUnitCombatant && defender is MapUnitCombatant && !defender.isCivilian() && !defender.isDefeated()) {
            for (unique in attacker.unit.getMatchingUniques(UniqueType.ExtraRangedAttack)) {
                val strength = (attacker.unit.baseUnit.strength * unique.params[0].toFloat() / 100).toInt()
                val extraAttack = Battle.FakeUnitForExtraRangedAttack(attacker, strength)
                minDamageToDefender += BattleDamage.calculateDamageToDefender(extraAttack, defender, origin, 0f)
                maxDamageToDefender += BattleDamage.calculateDamageToDefender(extraAttack, defender, origin, 1f)
            }
        }
        val defenderDamageLimit = (defender.getHealth() - if (defender is CityCombatant) 1 else 0).coerceAtLeast(0)
        return AttackPreview(attacker, defender, origin, defender.getTile(), path, cost,
            BattleDamage.getAttackingStrength(attacker, defender, origin),
            BattleDamage.getDefendingStrength(attacker, defender, origin),
            BattleDamage.getAttackModifiers(attacker, defender, origin).toMap(),
            if (defender is MapUnitCombatant) BattleDamage.getDefenceModifiers(attacker, defender, origin).toMap()
            else emptyMap(),
            damage.minDamageToAttacker, damage.maxDamageToAttacker,
            minDamageToDefender.coerceIn(0, defenderDamageLimit), maxDamageToDefender.coerceIn(0, defenderDamageLimit))
    }
}
