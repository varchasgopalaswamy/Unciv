package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerGreatPersonOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(8, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = game.addCity(player, game.getTile(0, 0))
    private val otherCity = game.addCity(other, game.getTile(6, 0))
    private val operations = PlayerGreatPersonOperations(player)

    init {
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.tech.techsToResearch = arrayListOf("Pottery")
        player.tech.scienceOfLast8Turns.fill(25)
    }

    private fun person(ability: String, x: Int = 1, y: Int = 0): MapUnit {
        val base = game.createBaseUnit("Civilian", "Great Person - [Science]", ability).apply { movement = 2 }
        return game.addUnit(base.name, player, game.getTile(x, y))
    }

    @Test
    fun `queries are pure detached and unaffected by private rival counters`() {
        val scientist = game.addUnit("Great Scientist", player, game.getTile(1, 0))
        val before = json().toJson(game.gameInfo)
        val options = operations.options(scientist)
        repeat(3) { assertEquals(options, operations.options(scientist)) }
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(options.any { it.kind == UnitActionType.HurryResearch && it.science!! > 0 })
        assertTrue(options.any { it.improvement == "Academy" && it.description.isNotBlank() })
        other.greatPeople.greatPersonPointsCounter["Great Scientist"] = 900
        other.tech.scienceOfLast8Turns.fill(800)
        assertEquals(options, operations.options(scientist))
        try {
            (options as MutableList).clear()
            fail("Options must be immutable")
        } catch (_: UnsupportedOperationException) { }
        try {
            (options.first().reasons as MutableList).clear()
            fail("Reasons must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test
    fun `foreign inactive spectator destroyed and ordinary units reject without mutation`() {
        val scientist = game.addUnit("Great Scientist", player, game.getTile(1, 0))
        val foreign = game.addUnit("Great Scientist", other, game.getTile(5, 0))
        val warrior = game.addUnit("Warrior", player, game.getTile(1, 0))
        val before = json().toJson(game.gameInfo)
        assertTrue(operations.options(foreign).isEmpty())
        assertTrue(operations.options(warrior).isEmpty())
        assertFalse(operations.tryActivate(foreign, "research1"))
        assertFalse(PlayerGreatPersonOperations(other).tryActivate(foreign, "research1"))
        assertFalse(PlayerGreatPersonOperations(player, true).tryActivate(scientist, "research1"))
        assertFalse(operations.tryActivate(scientist, "missing"))
        assertEquals(before, json().toJson(game.gameInfo))
        scientist.destroy()
        val destroyed = json().toJson(game.gameInfo)
        assertFalse(operations.tryActivate(scientist, "research1"))
        assertEquals(destroyed, json().toJson(game.gameInfo))
    }

    @Test
    fun `research uses the ordinary science and overflow rules and consumes exactly once`() {
        val scientist = game.addUnit("Great Scientist", player, game.getTile(1, 0))
        val cost = player.tech.costOfTech("Pottery")
        val ui = UnitActions.getUnitActions(scientist, UnitActionType.HurryResearch).single()
        val option = operations.options(scientist).single { it.kind == UnitActionType.HurryResearch }
        assertEquals(option.title, ui.title)
        assertTrue(option.science!! >= cost)
        ui.action!!.invoke()
        assertTrue(player.tech.isResearched("Pottery"))
        assertTrue(player.tech.getOverflowScience() > 0)
        assertTrue(scientist.isDestroyed)
        assertTrue(player.popupAlerts.any { it.type == AlertType.TechResearched && it.value == "Pottery" })
        val after = json().toJson(game.gameInfo)
        assertFalse(operations.tryActivate(scientist, option.name))
        ui.action!!.invoke()
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `stale desktop research rechecks queue movement and turn`() {
        val scientist = game.addUnit("Great Scientist", player, game.getTile(1, 0))
        val action = UnitActions.getUnitActions(scientist, UnitActionType.HurryResearch).single().action!!
        player.tech.techsToResearch.clear()
        var before = json().toJson(game.gameInfo)
        action()
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(operations.options(scientist).first().reasons.any { it.contains("technology") })
        player.tech.techsToResearch.add("Pottery")
        scientist.currentMovement = 0f
        before = json().toJson(game.gameInfo)
        action()
        assertEquals(before, json().toJson(game.gameInfo))
        scientist.currentMovement = 2f
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        before = json().toJson(game.gameInfo)
        action()
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test
    fun `ordinary building hurry leaves one production and refuses units`() {
        val engineer = person("Can speed up construction of a building", 0, 0)
        val building = game.createBuilding().apply { cost = 500 }
        city.cityConstructions.constructionQueue = arrayListOf(building.name)
        val option = operations.options(engineer).single()
        val initial = city.cityConstructions.getWorkDone(building.name)
        assertTrue(option.reasons.toString(), option.available)
        assertTrue(operations.tryActivate(engineer, option.name))
        assertEquals(initial + option.production!!, city.cityConstructions.getWorkDone(building.name))
        assertFalse(city.cityConstructions.isBuilt(building.name))
        assertTrue(city.cityConstructions.getRemainingWork(building.name) >= 1)
        val second = person("Can speed up construction of a building", 0, 0)
        city.cityConstructions.constructionQueue = arrayListOf("Warrior")
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.options(second).single().available)
        assertFalse(operations.tryActivate(second, "construction1"))
        assertEquals(before, json().toJson(game.gameInfo))
    }

    @Test
    fun `wonder hurry completes through construction effects`() {
        val engineer = person("Can speed up the construction of a wonder", 0, 0)
        val wonder = game.createWonder().apply { cost = 10 }
        city.cityConstructions.constructionQueue = arrayListOf(wonder.name)
        val option = operations.options(engineer).single()
        assertTrue(option.reasons.toString(), option.available)
        assertTrue(operations.tryActivate(engineer, option.name))
        assertTrue(city.cityConstructions.isBuilt(wonder.name))
        assertTrue(engineer.isDestroyed)
    }

    @Test
    fun `golden age extends current duration and delivers its ordinary message once`() {
        val artist = person("Empire enters a [8]-turn Golden Age <by consuming this unit>")
        player.goldenAges.turnsLeftForCurrentGoldenAge = 2
        val option = operations.options(artist).single()
        val ui = UnitActions.getUnitActions(artist, UnitActionType.TriggerUnique).single()
        assertEquals(option.title, ui.title)
        ui.action!!.invoke()
        assertTrue(artist.isDestroyed)
        assertEquals(2 + option.goldenAgeTurns!!, player.goldenAges.turnsLeftForCurrentGoldenAge)
        assertEquals(1, player.popupAlerts.count { it.type == AlertType.GoldenAge })
        val after = json().toJson(game.gameInfo)
        ui.action!!.invoke()
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `all five Vanilla improvements replace the tile and consume the person`() {
        for (name in listOf("Academy", "Manufactory", "Customs house", "Landmark", "Citadel")) {
            val unit = person("Can instantly construct a [$name] improvement <by consuming this unit>")
            val tile = unit.currentTile
            tile.setImprovement("Farm", player)
            val option = operations.options(unit).single()
            val ui = UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, tile).single()
            assertTrue(option.reasons.toString(), option.available)
            assertEquals(option.title, ui.title)
            ui.action!!.invoke()
            assertEquals(name, tile.improvement)
            assertTrue(unit.isDestroyed)
            val after = json().toJson(game.gameInfo)
            ui.action!!.invoke()
            assertEquals(after, json().toJson(game.gameInfo))
        }
    }

    @Test
    fun `improvements refuse city centers and reveal no unknown resource`() {
        val unit = person("Can instantly construct a [Academy] improvement <by consuming this unit>", 0, 0)
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.options(unit).single().available)
        assertFalse(operations.tryActivate(unit, "improvement1"))
        assertEquals(before, json().toJson(game.gameInfo))
        unit.destroy()
        val scientist = game.addUnit("Great Scientist", player, game.getTile(1, 0))
        val options = operations.options(scientist)
        scientist.currentTile.tileResource = game.ruleset.tileResources.getValue("Uranium")
        scientist.currentTile.resourceAmount = 9
        assertEquals(options, operations.options(scientist))
    }

    @Test
    fun `culture ability grants culture without choosing a policy`() {
        val writer = person("Can generate a large amount of culture")
        player.policies.endTurn(30)
        val before = player.policies.storedCulture
        val policies = player.policies.getAdoptedPolicies().toSet()
        val option = operations.options(writer).single()
        assertTrue(option.culture!! > 0)
        assertTrue(operations.tryActivate(writer, option.name))
        assertEquals(before + option.culture!!, player.policies.storedCulture)
        assertEquals(policies, player.policies.getAdoptedPolicies())
        assertTrue(writer.isDestroyed)
    }

    @Test
    fun `trade mission requires peace and delivers gold and influence once`() {
        val state = game.addCiv(cityStateType = "Cultured")
        val stateCity = game.addCity(state, game.getTile(0, 6))
        player.diplomacyFunctions.makeCivilizationsMeet(state)
        val missionTile = stateCity.getCenterTile().neighbors.first { it.getOwner() === state }
        val merchant = game.addUnit("Great Merchant", player, missionTile)
        val option = operations.options(merchant).single { it.kind == UnitActionType.ConductTradeMission }
        assertTrue(option.reasons.toString(), option.available)
        val gold = player.gold
        val influence = state.getDiplomacyManager(player)!!.getInfluence()
        assertTrue(operations.tryActivate(merchant, option.name))
        assertTrue(player.gold > gold)
        assertTrue(state.getDiplomacyManager(player)!!.getInfluence() > influence)
        assertTrue(merchant.isDestroyed)
        val after = json().toJson(game.gameInfo)
        assertFalse(operations.tryActivate(merchant, option.name))
        assertEquals(after, json().toJson(game.gameInfo))
        val second = game.addUnit("Great Merchant", player, missionTile)
        player.getDiplomacyManager(state)!!.declareWar()
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.options(second).single { it.kind == UnitActionType.ConductTradeMission }.available)
        assertFalse(operations.tryActivate(second, option.name))
        assertEquals(before, json().toJson(game.gameInfo))
    }
}
