package com.unciv.logic.civilization

import com.unciv.logic.civilization.PlayerTurnRequirements.Kind
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerOperationsTest {
    private val testGame = TestGame().apply { makeHexagonalMap(5) }
    private val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = testGame.addCiv(testGame.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val city = testGame.addCity(player, testGame.getTile(0, 0))
    private val foreignCity = testGame.addCity(other, testGame.getTile(4, 0))
    private val operations = PlayerOperations(player)

    init {
        testGame.gameInfo.currentPlayer = player.civID
        testGame.gameInfo.currentPlayerCiv = player
    }

    @Test
    fun `rename validates identity ownership input and spectator mode`() {
        val original = city.name
        for (name in listOf("", "x".repeat(33), "a[b]", "a\nb", "a\u0000b")) {
            assertFalse(operations.tryRenameCity(city, name))
            assertEquals(original, city.name)
        }
        assertFalse(operations.tryRenameCity(foreignCity, "Stolen"))
        assertFalse(PlayerOperations(player, spectatorMode = true).tryRenameCity(city, "Spectator"))
        assertFalse(GameView(testGame.gameInfo, player, spectatorMode = true).getCityView(city).tryRenameCity("Spectator"))
        assertFalse(PlayerOperations(other).tryRenameCity(foreignCity, "Out of turn"))
        assertTrue(operations.tryRenameCity(city, "Roma"))
        assertEquals("Roma", city.name)
        testGame.gameInfo.currentPlayerCiv = other
        assertFalse(operations.tryRenameCity(city, "Inconsistent current player"))
    }

    @Test
    fun `research queue rejects missing prerequisites without changing prior research`() {
        val available = testGame.ruleset.technologies.keys.first { player.tech.canBeResearched(it) }
        val requested = arrayListOf(available)
        assertTrue(operations.trySetResearchQueue(requested))
        requested.clear()
        assertEquals(listOf(available), player.tech.techsToResearch)
        val unavailable = testGame.ruleset.technologies.keys.first {
            !player.tech.isResearched(it) && !player.tech.canBeResearched(it)
        }
        for (queue in listOf(emptyList(), listOf("Unknown"), listOf(unavailable), listOf(available, available))) {
            assertFalse(operations.trySetResearchQueue(queue))
            assertEquals(listOf(available), player.tech.techsToResearch)
        }
    }

    @Test
    fun `free technology revalidates entitlement and prerequisites`() {
        val available = testGame.ruleset.technologies.keys.first { player.tech.canBeResearched(it) }
        assertFalse(operations.tryChooseFreeTechnology(available))
        assertFalse(player.tech.isResearched(available))
        player.tech.freeTechs = 1
        assertFalse(operations.tryChooseFreeTechnology("Unknown"))
        assertEquals(1, player.tech.freeTechs)
        assertFalse(operations.trySetResearchQueue(listOf(available)))
        assertTrue(operations.tryChooseFreeTechnology(available))
        assertTrue(player.tech.isResearched(available))
        assertEquals(0, player.tech.freeTechs)
        assertFalse(operations.tryChooseFreeTechnology(available))
    }

    @Test
    fun `policy revalidates cost and cannot grant a branch completion directly`() {
        player.policies.storedCulture = 0
        assertFalse(operations.tryAdoptPolicy("Tradition"))
        player.policies.freePolicies = 1
        assertFalse(operations.tryAdoptPolicy("Tradition Complete"))
        assertFalse(operations.tryAdoptPolicy("Unknown"))
        assertTrue(operations.tryAdoptPolicy("Tradition"))
        assertEquals(0, player.policies.freePolicies)
        assertTrue(player.policies.isAdopted("Tradition"))
        assertFalse(operations.tryAdoptPolicy("Tradition"))
    }

    @Test
    fun `requirements are shared with views ordered and read only`() {
        city.cityConstructions.constructionQueue.clear()
        player.tech.techsToResearch.clear()
        player.tech.freeTechs = 1
        player.policies.freePolicies = 1
        player.greatPeople.freeGreatPeople = 1
        val pending = PlayerTurnRequirements.pending(player)
        assertEquals(listOf(Kind.FreeGreatPerson, Kind.Construction, Kind.Research, Kind.Policy), pending.take(4))
        val view = GameView(testGame.gameInfo, player).civView
        for (kind in Kind.entries) assertEquals(kind in pending, view.hasPendingTurnRequirement(kind))
        assertTrue(view.cityNeedingConstruction() != null)
        assertEquals(pending, PlayerTurnRequirements.pending(player))
        assertEquals(1, player.tech.freeTechs)
        assertEquals(1, player.policies.freePolicies)
        assertEquals(1, player.greatPeople.freeGreatPeople)
        city.isPuppet = true
        assertFalse(PlayerTurnRequirements.isPending(player, Kind.Construction))
        assertNull(view.cityNeedingConstruction())
    }

    @Test
    fun `construction rejects unknown foreign and duplicate building entries`() {
        city.cityConstructions.constructionQueue.clear()
        val original = foreignCity.cityConstructions.constructionQueue.toList()
        assertFalse(operations.tryQueueConstruction(foreignCity, "Worker"))
        assertEquals(original, foreignCity.cityConstructions.constructionQueue)
        assertFalse(operations.tryQueueConstruction(city, "Unknown"))
        assertTrue(city.cityConstructions.constructionQueue.isEmpty())
        assertTrue(operations.tryQueueConstruction(city, "Monument"))
        assertFalse(operations.tryQueueConstruction(city, "Monument"))
        assertEquals(listOf("Monument"), city.cityConstructions.constructionQueue)
    }

    @Test
    fun `acknowledgement cannot bypass a consequential alert or consume a foreign alert`() {
        val information = PopupAlert(AlertType.TechResearched, "Agriculture")
        val decision = PopupAlert(AlertType.CityConquered, city.id)
        val foreign = PopupAlert(AlertType.TechResearched, "Agriculture")
        player.popupAlerts.add(information)
        player.popupAlerts.add(decision)
        other.popupAlerts.add(foreign)
        assertFalse(operations.tryAcknowledgeAlert(foreign))
        assertFalse(operations.tryAcknowledgeAlert(decision))
        assertTrue(operations.tryAcknowledgeAlert(information))
        assertFalse(operations.tryAcknowledgeAlert(information))
        assertTrue(decision in player.popupAlerts)
        assertTrue(foreign in other.popupAlerts)
    }

    @Test
    fun `dismissing a policy picker does not spend culture or postpone a free choice`() {
        player.policies.freePolicies = 1
        player.policies.shouldOpenPolicyPicker = true
        val culture = player.policies.storedCulture
        assertTrue(operations.tryDismissPolicyPicker())
        assertEquals(culture, player.policies.storedCulture)
        assertEquals(1, player.policies.freePolicies)
        assertTrue(PlayerTurnRequirements.isPending(player, Kind.Policy))
        assertFalse(PlayerOperations(other).tryDismissPolicyPicker())
    }

    @Test
    fun `research accepts a complete prerequisite path with a detached queue`() {
        val destination = testGame.ruleset.technologies.getValue("Writing")
        val path = player.tech.getRequiredTechsToDestination(destination).map { it.name }
        assertTrue(path.size > 1)
        assertTrue(operations.trySetResearchQueue(path))
        assertEquals(path, player.tech.techsToResearch)
        assertFalse(operations.trySetResearchQueue(path.reversed()))
        assertEquals(path, player.tech.techsToResearch)
    }
}
