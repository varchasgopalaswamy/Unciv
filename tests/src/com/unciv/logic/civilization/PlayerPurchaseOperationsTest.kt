package com.unciv.logic.civilization

import com.unciv.logic.city.CityFlags
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerPurchaseOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(6, "Grassland") }
    private val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = testGame.addCity(player, testGame.getTile(0, 0))
    private val foreign = testGame.addCity(other, testGame.getTile(5, 0))
    private val operations = PlayerPurchaseOperations(player)

    init {
        testGame.gameInfo.currentPlayer = player.civID
        testGame.gameInfo.currentPlayerCiv = player
        player.addGold(5000)
        player.cache.updateViewableTiles()
        city.cityConstructions.constructionQueue = arrayListOf("Worker", "Scout")
    }

    private fun option(name: String) = operations.constructionOptions(city).single { it.name == name }

    @Test
    fun `gold unit and building purchases use native costs placement and queue semantics`() {
        val worker = option("Worker")
        val gold = player.gold
        assertTrue(worker.available)
        assertEquals(testGame.ruleset.units.getValue("Worker").getStatBuyCost(city, Stat.Gold), worker.goldCost)
        assertTrue(operations.tryBuyConstruction(city, "Worker", worker.goldCost, queuePosition = 0))
        val unit = player.units.getCivUnits().single()
        assertEquals("Worker", unit.name)
        assertSame(city.getCenterTile(), unit.currentTile)
        assertEquals(0f, unit.currentMovement, 0f)
        assertEquals(gold - worker.goldCost!!, player.gold)
        assertEquals(listOf("Scout"), city.cityConstructions.constructionQueue)
        assertFalse(option("Worker").available)
        val monument = option("Monument")
        assertTrue(monument.available)
        assertTrue(operations.tryBuyConstruction(city, "Monument", monument.goldCost))
        assertTrue(city.cityConstructions.isBuilt("Monument"))
        assertEquals(gold - worker.goldCost!! - monument.goldCost!!, player.gold)
        assertFalse(operations.tryBuyConstruction(city, "Monument"))
    }

    @Test
    fun `cost changes bad queue selection and insufficient funds reject without mutation`() {
        val cost = option("Worker").goldCost!!
        val tileOffer = operations.tileOptions(city).first { it.available }
        val tile = testGame.getTile(tileOffer.x, tileOffer.y)
        val gold = player.gold
        val queue = city.cityConstructions.constructionQueue.toList()
        assertFalse(operations.tryBuyConstruction(city, "Worker", expectedGoldCost = cost + 1))
        assertFalse(operations.tryBuyConstruction(city, "Worker", queuePosition = 1))
        assertFalse(operations.tryBuyConstruction(city, "Worker", queuePosition = -2))
        assertFalse(operations.tryBuyConstruction(city, "Unknown"))
        assertEquals(gold, player.gold)
        player.addGold(-player.gold)
        assertFalse(option("Worker").available)
        assertTrue(option("Worker").unavailableReasons.contains("Not enough gold"))
        assertFalse(operations.tryBuyConstruction(city, "Worker"))
        assertFalse(operations.tryBuyTile(city, tile))
        assertNull(tile.getOwner())
        assertEquals(0, player.gold)
        assertEquals(queue, city.cityConstructions.constructionQueue)
        assertEquals(0, player.units.getCivUnits().count())
    }

    @Test
    fun `queued buildings remain purchasable and remove only the selected entry`() {
        city.cityConstructions.constructionQueue = arrayListOf("Monument", "Worker")
        assertFalse(testGame.ruleset.buildings.getValue("Monument").shouldBeDisplayed(city.cityConstructions))
        val monument = option("Monument")
        assertTrue(monument.available)
        assertTrue(operations.tryBuyConstruction(city, "Monument", monument.goldCost, 0))
        assertTrue(city.cityConstructions.isBuilt("Monument"))
        assertEquals(listOf("Worker"), city.cityConstructions.constructionQueue)
    }

    @Test
    fun `placement choice purchases retain the separate native workflow`() {
        val building = testGame.createBuilding("Creates a [Farm] improvement on a specific tile").apply { cost = 80 }
        val gold = player.gold
        val result = option(building.name)
        assertFalse(result.available)
        assertTrue(result.unavailableReasons.contains("This construction requires a tile placement choice"))
        assertFalse(operations.tryBuyConstruction(city, building.name))
        assertEquals(gold, player.gold)
        assertFalse(city.cityConstructions.isBuilt(building.name))
        assertTrue(city.getTiles().none { it.isMarkedForCreatesOneImprovement() })
    }

    @Test
    fun `resource requirements and unavailable reasons follow visible city choices`() {
        player.tech.addTechnology("Iron Working", showNotification = false)
        val legion = option("Legion")
        assertEquals(1, legion.resourceRequirements["Iron"])
        assertFalse(legion.available)
        assertTrue(legion.unavailableReasons.any { it.contains("Iron") || it.contains("resources") })
        val gold = player.gold
        assertFalse(operations.tryBuyConstruction(city, "Legion"))
        assertEquals(gold, player.gold)
        assertEquals(0, player.units.getCivUnits().count())
    }

    @Test
    fun `buying tiles rechecks contiguity visibility price and ownership`() {
        val offered = operations.tileOptions(city).first { it.available }
        val tile = testGame.getTile(offered.x, offered.y)
        val gold = player.gold
        assertFalse(operations.tryBuyTile(city, tile, offered.goldCost + 1))
        assertEquals(gold, player.gold)
        assertNull(tile.getOwner())
        assertTrue(operations.tryBuyTile(city, tile, offered.goldCost))
        assertSame(city, tile.getCity())
        assertEquals(gold - offered.goldCost, player.gold)
        assertFalse(operations.tryBuyTile(city, tile))
        assertFalse(operations.tryBuyTile(city, foreign.getCenterTile()))
        assertFalse(operations.tryBuyTile(city, testGame.getTile(-6, 0)))
        assertEquals(gold - offered.goldCost, player.gold)
        val next = operations.tileOptions(city).first { it.available }
        val nextTile = testGame.getTile(next.x, next.y)
        player.viewableTiles = player.viewableTiles.filterNot { it === nextTile }.toSet()
        assertFalse(operations.tryBuyTile(city, nextTile))
        assertFalse(operations.tileOptions(city).any { it.x == next.x && it.y == next.y })
    }

    @Test
    fun `foreign inactive spectator puppet and resisting cities cannot purchase`() {
        val tileOffer = operations.tileOptions(city).first { it.available }
        val tile = testGame.getTile(tileOffer.x, tileOffer.y)
        val gold = player.gold
        fun rejects(ops: PlayerPurchaseOperations) {
            assertFalse(ops.tryBuyConstruction(city, "Worker"))
            assertFalse(ops.tryBuyTile(city, tile))
            assertEquals(gold, player.gold)
            assertEquals(0, player.units.getCivUnits().count())
            assertNull(tile.getOwner())
        }
        rejects(PlayerPurchaseOperations(other))
        assertTrue(PlayerPurchaseOperations(other).constructionOptions(city).isEmpty())
        rejects(PlayerPurchaseOperations(player, spectatorMode = true))
        city.isPuppet = true
        rejects(operations)
        city.isPuppet = false
        city.setFlag(CityFlags.Resistance, 2)
        rejects(operations)
        city.removeFlag(CityFlags.Resistance)
        testGame.gameInfo.currentPlayerCiv = other
        rejects(operations)
    }

    @Test
    fun `city view delegates gold and tile purchases including confirmation price checks`() {
        val view = GameView(testGame.gameInfo, player)
        val cityView = view.getCityView(city)
        val worker = testGame.ruleset.units.getValue("Worker")
        val cost = option("Worker").goldCost!!
        assertTrue(cityView.constructions.isConstructionPurchaseAllowed(worker, Stat.Gold, cost))
        assertFalse(cityView.constructions.purchaseConstruction(worker, 0, Stat.Gold, null, cost + 1))
        assertTrue(cityView.constructions.purchaseConstruction(worker, 0, Stat.Gold, null, cost))
        val tileOffer = operations.tileOptions(city).first { it.available }
        val tile = view.tileMapView.getTile(testGame.getTile(tileOffer.x, tileOffer.y))
        assertFalse(cityView.tryBuyTile(tile, tileOffer.goldCost + 1))
        assertTrue(cityView.tryBuyTile(tile, tileOffer.goldCost))
        val spectatorView = GameView(testGame.gameInfo, player, spectatorMode = true).getCityView(city)
        assertFalse(spectatorView.constructions.purchaseConstruction(testGame.ruleset.buildings.getValue("Monument"), -1, Stat.Gold, null))
    }

    @Test
    fun `queries do not allocate units or inspect hidden placement capacity`() {
        val id = testGame.gameInfo.getNextUnitId()
        val before = operations.constructionOptions(city)
        val hidden = testGame.getTile(-5, 0)
        assertFalse(hidden.isVisible(player))
        testGame.addUnit("Warrior", other, hidden)
        val afterEnemy = testGame.gameInfo.getNextUnitId()
        assertEquals(before, operations.constructionOptions(city))
        operations.tileOptions(city)
        assertEquals(afterEnemy + 1, testGame.gameInfo.getNextUnitId())
        assertTrue(afterEnemy > id)
    }
}
