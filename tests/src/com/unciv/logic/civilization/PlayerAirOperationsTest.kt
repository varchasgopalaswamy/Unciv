package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.battle.AttackResolution
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.civilization.PlayerAirOperations.Mission
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.attackEventsForTesting
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerAirOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(16) }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val home = game.addCity(player, game.getTile(0, 0))
    private val base = game.addCity(player, game.getTile(-5, 0))
    private val enemyBase = game.addCity(other, game.getTile(7, 0))
    private val operations = PlayerAirOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(other)
        reveal()
    }

    private fun reveal() {
        for (tile in game.tileMap.values) tile.setExplored(player, true)
        player.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `rebasing uses native range capacity and movement without mutating its preview`() {
        val fighter = game.addUnit("Fighter", player, home.getCenterTile())
        val target = base.getCenterTile()
        val before = json().toJson(game.gameInfo)
        val preview = operations.preview(fighter, Mission.REBASE, target)!!
        assertTrue(preview.reasons.toString(), preview.available)
        assertEquals(fighter.getRange() * 2, preview.range)
        assertEquals(before, json().toJson(game.gameInfo))
        assertFalse(operations.preview(fighter, Mission.REBASE, enemyBase.getCenterTile())!!.available)
        assertFalse(operations.preview(fighter, Mission.REBASE, game.getTile(16, 0))!!.available)
        assertFalse(PlayerAirOperations(player, true).tryExecute(fighter, Mission.REBASE, target))
        assertTrue(operations.tryExecute(fighter, Mission.REBASE, target))
        assertSame(target, fighter.currentTile)
        assertEquals(0f, fighter.currentMovement, 0f)
        assertFalse(operations.tryExecute(fighter, Mission.REBASE, home.getCenterTile()))
    }

    @Test
    fun `rebasing rechecks a destination that filled after preview`() {
        val fighter = game.addUnit("Fighter", player, home.getCenterTile())
        val target = base.getCenterTile()
        assertTrue(operations.preview(fighter, Mission.REBASE, target)!!.available)
        repeat(base.getMaxAirUnits()) { game.addUnit("Fighter", player, target) }
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.tryExecute(fighter, Mission.REBASE, target))
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test
    fun `aircraft travel with carriers and are lost when their carrier is destroyed`() {
        val sea = game.getTile(2, 0).apply { baseTerrain = "Coast"; setTerrainTransients() }
        val next = game.getTile(3, 0).apply { baseTerrain = "Coast"; setTerrainTransients() }
        val carrier = game.addUnit("Carrier", player, sea)
        val fighter = game.addUnit("Fighter", player, home.getCenterTile())
        assertTrue(operations.tryExecute(fighter, Mission.REBASE, sea))
        assertTrue(fighter.isTransported)
        assertTrue(PlayerUnitOperations(player).tryMove(carrier, next))
        assertSame(next, fighter.currentTile)
        assertTrue(fighter.isTransported)
        carrier.destroy()
        assertTrue(fighter.isDestroyed)
    }

    @Test
    fun `carrier restrictions distinguish aircraft and missiles`() {
        val sea = game.getTile(2, 0).apply { baseTerrain = "Coast"; setTerrainTransients() }
        val carrier = game.addUnit("Carrier", player, sea)
        val missile = game.addUnit("Guided Missile", player, home.getCenterTile())
        assertFalse(operations.preview(missile, Mission.REBASE, sea)!!.available)
        carrier.destroy()
        game.addUnit("Nuclear Submarine", player, sea)
        assertTrue(operations.tryExecute(missile, Mission.REBASE, sea))
        assertTrue(missile.isTransported)
        assertEquals(0f, missile.currentMovement, 0f)
    }

    @Test
    fun `paradrops derive conditional native filters without UI preparation`() {
        val paratrooper = game.addUnit("Paratrooper", player, home.getCenterTile())
        val destination = game.getTile(4, 0)
        reveal()
        assertTrue(paratrooper.cache.paradropDestinationTileFilters.isEmpty())
        val before = json().toJson(game.gameInfo)
        assertTrue(operations.preview(paratrooper, Mission.PARADROP, destination)!!.available)
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(paratrooper.cache.paradropDestinationTileFilters.isEmpty())
        assertTrue(operations.tryExecute(paratrooper, Mission.PARADROP, destination))
        assertSame(destination, paratrooper.currentTile)
        assertEquals(1, paratrooper.attacksThisTurn)
        assertNull(paratrooper.action)
        assertFalse(operations.tryExecute(paratrooper, Mission.PARADROP, home.getCenterTile()))
    }

    @Test
    fun `paradrops reject fog occupied water and movement already used`() {
        val unit = game.addUnit("Paratrooper", player, home.getCenterTile())
        val target = game.getTile(4, 0)
        player.viewableTiles = setOf(home.getCenterTile())
        assertFalse(operations.preview(unit, Mission.PARADROP, target)!!.available)
        reveal()
        target.baseTerrain = "Coast"
        target.setTerrainTransients()
        assertFalse(operations.preview(unit, Mission.PARADROP, target)!!.available)
        target.baseTerrain = "Grassland"
        target.setTerrainTransients()
        val occupant = game.addUnit("Warrior", player, target)
        assertFalse(operations.preview(unit, Mission.PARADROP, target)!!.available)
        occupant.destroy()
        unit.currentMovement -= 1f
        assertFalse(operations.preview(unit, Mission.PARADROP, target)!!.available)
    }

    @Test
    fun `air strikes use visible native targets and do not rebase`() {
        val bomber = game.addUnit("Bomber", player, home.getCenterTile())
        val target = game.addUnit("Warrior", other, game.getTile(4, 0))
        val combat = PlayerCombatOperations(player)
        assertTrue(combat.attacks(bomber).isEmpty())
        player.getDiplomacyManager(other)!!.declareWar()
        reveal()
        val offer = combat.attacks(bomber).first { it.target === target.currentTile }
        assertTrue(offer.path.isEmpty())
        assertSame(home.getCenterTile(), offer.attackFrom)
        val result = combat.tryAttack(bomber, offer.target, offer.attackFrom)!!
        assertTrue(result.attacked && result.damageToDefender > 0)
        assertSame(home.getCenterTile(), bomber.currentTile)
        assertNull(combat.tryAttack(bomber, offer.target, offer.attackFrom))
    }

    @Test
    fun `fatal interception leaves intended defender unharmed`() {
        val bomber = game.addUnit("Bomber", player, home.getCenterTile()).apply { health = 1 }
        val target = game.addUnit("Warrior", other, game.getTile(4, 0))
        val interceptor = game.addUnit("Fighter", other, enemyBase.getCenterTile())
        player.getDiplomacyManager(other)!!.declareWar()
        reveal()
        val combat = PlayerCombatOperations(player)
        val offer = combat.attacks(bomber).first { it.target === target.currentTile }
        val result = combat.tryAttack(bomber, offer.target, offer.attackFrom)!!
        assertTrue(result.attackerDestroyed)
        assertEquals(0, result.damageToDefender)
        assertEquals(AttackResolution.Intercepted, result.resolution)
        assertEquals(100, target.health)
        assertEquals(1, interceptor.attacksThisTurn)
        assertEquals(AttackResolution.Intercepted, game.gameInfo.attackEventsForTesting.last().resolution)
    }

    @Test
    fun `guided missiles use native strike consumption`() {
        val missile = game.addUnit("Guided Missile", player, home.getCenterTile())
        val target = game.addUnit("Warrior", other, game.getTile(3, 0))
        player.getDiplomacyManager(other)!!.declareWar()
        reveal()
        val combat = PlayerCombatOperations(player)
        val offer = combat.attacks(missile).first { it.target === target.currentTile }
        val result = combat.tryAttack(missile, offer.target, offer.attackFrom)!!
        assertTrue(result.attacked && result.attackerDestroyed)
        assertTrue(missile.isDestroyed)
    }

    @Test
    fun `sweeps consume enemy interception and no query reveals the interceptor`() {
        val fighter = game.addUnit("Fighter", player, home.getCenterTile())
        player.getDiplomacyManager(other)!!.declareWar()
        val target = game.getTile(4, 0)
        val before = operations.preview(fighter, Mission.AIR_SWEEP, target)!!
        val interceptor = game.addUnit("Fighter", other, enemyBase.getCenterTile())
        assertEquals(before, operations.preview(fighter, Mission.AIR_SWEEP, target))
        assertTrue(before.visibleVictims.isEmpty())
        assertTrue(operations.tryExecute(fighter, Mission.AIR_SWEEP, target))
        assertEquals(1, fighter.attacksThisTurn)
        assertEquals(1, interceptor.attacksThisTurn)
        assertTrue(fighter.health < 100 && interceptor.health < 100)
        assertFalse(interceptor.canIntercept())
        assertFalse(operations.tryExecute(fighter, Mission.AIR_SWEEP, target))
    }

    @Test
    fun `unopposed sweep remains a real expended mission`() {
        val fighter = game.addUnit("Fighter", player, home.getCenterTile())
        assertTrue(operations.tryExecute(fighter, Mission.AIR_SWEEP, game.getTile(4, 0)))
        assertEquals(1, fighter.attacksThisTurn)
        assertEquals(0f, fighter.currentMovement, 0f)
        assertEquals(100, fighter.health)
    }

    @Test
    fun `nuclear previews ignore unseen victims but execution keeps native restrictions`() {
        val nuke = game.addUnit("Atomic Bomb", player, home.getCenterTile())
        val target = game.getTile(4, 0)
        player.viewableTiles = setOf(home.getCenterTile())
        val before = operations.preview(nuke, Mission.NUCLEAR_STRIKE, target)!!
        val stranger = game.addCiv(isPlayer = true)
        game.addCity(stranger, game.getTile(12, 0))
        game.addUnit("Warrior", stranger, target)
        assertEquals(before, operations.preview(nuke, Mission.NUCLEAR_STRIKE, target))
        assertTrue(before.available && before.speculative)
        assertTrue(before.visibleVictims.isEmpty())
        assertFalse(Nuke.mayUseNuke(MapUnitCombatant(nuke), target))
        val state = json().toJson(game.gameInfo)
        assertFalse(operations.tryExecute(nuke, Mission.NUCLEAR_STRIKE, target))
        assertEquals(state, json().toJson(game.gameInfo))
    }

    @Test
    fun `nuclear strikes declare native wars damage targets and consume weapon`() {
        for (technology in game.ruleset.technologies.keys) player.tech.addTechnology(technology)
        home.getCenterTile().tileResource = game.ruleset.tileResources.getValue("Uranium")
        home.getCenterTile().resourceAmount = 8
        player.cache.updateCivResources()
        val nuke = game.addUnit("Nuclear Missile", player, home.getCenterTile())
        val target = enemyBase.getCenterTile()
        val victim = game.addUnit("Warrior", other, target)
        reveal()
        val preview = operations.preview(nuke, Mission.NUCLEAR_STRIKE, target)!!
        assertTrue(preview.available)
        assertTrue(preview.knownDeclarations.contains(other))
        assertTrue(preview.visibleVictims.any { it is CityCombatant })
        assertTrue(operations.tryExecute(nuke, Mission.NUCLEAR_STRIKE, target))
        assertTrue(player.isAtWarWith(other))
        assertTrue(nuke.isDestroyed)
        assertTrue(victim.isDestroyed)
        assertEquals(AttackResolution.Completed, game.gameInfo.attackEventsForTesting.last().resolution)
    }

    @Test
    fun `aircraft upgrade reuses its slot in a full city and preserves identity`() {
        for (technology in game.ruleset.technologies.keys) player.tech.addTechnology(technology)
        home.getCenterTile().tileResource = game.ruleset.tileResources.getValue("Aluminum")
        home.getCenterTile().resourceAmount = 8
        player.cache.updateCivResources()
        player.addGold(2000)
        val fighter = game.addUnit("Fighter", player, home.getCenterTile()).apply { health = 62; promotions.XP = 19 }
        repeat(home.getMaxAirUnits() - 1) { game.addUnit("Fighter", player, home.getCenterTile()) }
        val economy = PlayerUnitEconomyOperations(player)
        val option = economy.upgrades(fighter).first { it.targetName == "Jet Fighter" }
        assertTrue(option.reasons.toString(), option.available)
        val action = UnitActionsUpgrade.getUpgradeActions(fighter).first().action!!
        val gold = player.gold
        action()
        val replacement = player.units.getUnitById(fighter.id)!!
        assertEquals("Jet Fighter", replacement.name)
        assertSame(home.getCenterTile(), replacement.currentTile)
        assertEquals(62, replacement.health)
        assertEquals(19, replacement.promotions.XP)
        assertEquals(gold - option.goldCost, player.gold)
        assertEquals(home.getMaxAirUnits(), home.getCenterTile().airUnits.size)
    }

    @Test
    fun `aircraft upgrade reuses a full carrier slot and rechecks payment`() {
        for (technology in game.ruleset.technologies.keys) player.tech.addTechnology(technology)
        home.getCenterTile().tileResource = game.ruleset.tileResources.getValue("Aluminum")
        home.getCenterTile().resourceAmount = 8
        player.cache.updateCivResources()
        val sea = game.getTile(1, 0).apply { baseTerrain = "Coast"; setTerrainTransients() }
        val carrier = game.addUnit("Carrier", player, sea)
        val fighter = game.addUnit("Fighter", player, sea)
        val capacity = 1 + carrier.checkCarryCapacity(fighter)
        repeat(capacity - 1) { game.addUnit("Fighter", player, sea) }
        assertEquals(0, carrier.checkCarryCapacity(fighter))
        val economy = PlayerUnitEconomyOperations(player)
        val option = economy.upgrades(fighter).single { it.targetName == "Jet Fighter" }
        assertFalse(option.available)
        assertNull(economy.tryUpgrade(fighter, "Jet Fighter"))
        player.addGold(option.goldCost)
        val replacement = economy.tryUpgrade(fighter, "Jet Fighter")!!
        assertEquals(0, player.gold)
        assertEquals(fighter.id, replacement.id)
        assertSame(sea, replacement.currentTile)
        assertTrue(replacement.isTransported)
        assertEquals(capacity, sea.airUnits.size)
        assertEquals(0, carrier.checkCarryCapacity(replacement))
    }

    @Test
    fun `nuclear interception returns the resolution without exposing hidden victims`() {
        val bomb = game.addUnit("Atomic Bomb", player, home.getCenterTile()).apply { health = 1 }
        val victim = game.addUnit("Warrior", other, game.getTile(4, 0))
        game.addUnit("Fighter", other, enemyBase.getCenterTile())
        reveal()
        val result = operations.tryExecuteWithResult(bomb, Mission.NUCLEAR_STRIKE, victim.currentTile)!!
        assertEquals(AttackResolution.Intercepted, result.resolution)
        assertTrue(bomb.isDestroyed)
        assertEquals(100, victim.health)
        assertTrue(player.isAtWarWith(other)) // Native war declaration precedes interception.
    }

    @Test
    fun `special missions reject foreign inactive and spectator control`() {
        val fighter = game.addUnit("Fighter", player, home.getCenterTile())
        val foreign = game.addUnit("Fighter", other, enemyBase.getCenterTile())
        val before = json().toJson(game.gameInfo)
        for (mission in Mission.entries) {
            assertFalse(operations.tryExecute(foreign, mission, base.getCenterTile()))
            assertFalse(PlayerAirOperations(player, true).tryExecute(fighter, mission, base.getCenterTile()))
        }
        assertEquals(before, json().toJson(game.gameInfo))
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        val inactive = json().toJson(game.gameInfo)
        for (mission in Mission.entries) assertFalse(operations.tryExecute(fighter, mission, base.getCenterTile()))
        assertEquals(inactive, json().toJson(game.gameInfo))
    }

    @Test
    fun `existing aircraft purchase promotion and disband obey native costs and capacity`() {
        for (technology in game.ruleset.technologies.keys) player.tech.addTechnology(technology)
        home.getCenterTile().tileResource = game.ruleset.tileResources.getValue("Aluminum")
        home.getCenterTile().resourceAmount = 20
        player.cache.updateCivResources()
        val purchases = PlayerPurchaseOperations(player)
        assertFalse(purchases.tryBuyConstruction(home, "Jet Fighter"))
        player.addGold(10000)
        assertTrue(purchases.tryBuyConstruction(home, "Jet Fighter"))
        val fighter = home.getCenterTile().airUnits.single()
        fighter.currentMovement = 1f
        fighter.promotions.XP = 100
        val orders = PlayerUnitOrders(player)
        val promotion = orders.promotions(fighter)!!.choices.first { it.available }
        assertTrue(orders.tryPromote(fighter, promotion.name))
        repeat(home.getMaxAirUnits() - 1) { game.addUnit("Fighter", player, home.getCenterTile()) }
        val before = json().toJson(game.gameInfo)
        assertFalse(purchases.tryBuyConstruction(home, "Jet Fighter"))
        assertEquals(before, json().toJson(game.gameInfo))
        fighter.currentMovement = 1f
        val gold = player.gold
        val economy = PlayerUnitEconomyOperations(player)
        val refund = economy.disband(fighter)!!.gold
        assertTrue(economy.tryDisband(fighter))
        assertTrue(fighter.isDestroyed)
        assertEquals(gold + refund, player.gold)
    }
}
