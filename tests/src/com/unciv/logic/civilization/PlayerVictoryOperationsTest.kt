package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PlayerVictoryOperationsTest {
    private val game = TestGame().apply { makeHexagonalMap(9, "Grassland") }
    private val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
    private val other = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
    private val hidden = game.addCiv(game.ruleset.nations.getValue("France"), isPlayer = true)
    private val capital = game.addCity(player, game.getTile(0, 0))
    private val operations = PlayerVictoryOperations(player)

    init {
        game.addCity(other, game.getTile(5, 0))
        game.addCity(hidden, game.getTile(-5, 0))
        game.gameInfo.currentPlayer = player.civID
        game.gameInfo.currentPlayerCiv = player
        player.diplomacyFunctions.makeCivilizationsMeet(other)
    }

    @Test
    fun `assembly shares desktop effects even without movement and stale callbacks cannot consume twice`() {
        val part = game.addUnit("SS Booster", player, capital.getCenterTile())
        part.currentMovement = 0f
        val before = json().toJson(game.gameInfo)
        val option = operations.spaceshipPart(part)!!
        assertTrue(option.available)
        val action = UnitActions.getUnitActions(part, UnitActionType.AddInCapital).single()
        assertEquals(option.title, action.title)
        assertEquals(before, json().toJson(game.gameInfo))
        action.action!!.invoke()
        assertTrue(part.isDestroyed)
        assertEquals(1, player.victoryManager.currentsSpaceshipParts["SS Booster"])
        val after = json().toJson(game.gameInfo)
        action.action!!.invoke()
        assertFalse(operations.tryAddSpaceshipPart(part))
        assertEquals(after, json().toJson(game.gameInfo))
    }

    @Test
    fun `assembly rejects wrong units locations authority and stale capital state without effects`() {
        val part = game.addUnit("SS Cockpit", player, game.getTile(1, 0))
        val foreign = game.addUnit("SS Booster", other, other.getCapital()!!.getCenterTile())
        val warrior = game.addUnit("Warrior", player, capital.getCenterTile())
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.tryAddSpaceshipPart(part))
        assertNull(operations.spaceshipPart(foreign))
        assertFalse(operations.tryAddSpaceshipPart(foreign))
        assertNull(operations.spaceshipPart(warrior))
        assertFalse(PlayerVictoryOperations(other).tryAddSpaceshipPart(foreign))
        assertFalse(PlayerVictoryOperations(player, true).tryAddSpaceshipPart(part))
        assertEquals(before, json().toJson(game.gameInfo))
        part.movement.moveToTile(capital.getCenterTile())
        val stale = UnitActions.getUnitActions(part, UnitActionType.AddInCapital).single().action!!
        game.gameInfo.currentPlayer = other.civID
        game.gameInfo.currentPlayerCiv = other
        val inactive = json().toJson(game.gameInfo)
        stale()
        assertEquals(inactive, json().toJson(game.gameInfo))
    }

    @Test
    fun `vote candidates match desktop and invalid choices never record a ballot`() {
        player.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
        val before = json().toJson(game.gameInfo)
        assertEquals(listOf(other.civID), operations.voteCandidates().map { it.civilizationId })
        assertTrue(operations.canVote())
        assertFalse(operations.tryVote(player.civID))
        assertFalse(operations.tryVote(hidden.civID))
        assertFalse(operations.tryVote("missing"))
        assertFalse(PlayerVictoryOperations(player, true).tryVote(other.civID))
        assertEquals(before, json().toJson(game.gameInfo))
        assertTrue(operations.tryVote(other.civID))
        assertEquals(other.civID, game.gameInfo.diplomaticVictoryVotesCast[player.civID])
        assertFalse(operations.canVote())
        assertFalse(operations.tryVote(null))
        assertFalse(PlayerTurnRequirements.isPending(player, PlayerTurnRequirements.Kind.DiplomaticVote))
    }

    @Test
    fun `abstention records a null ballot exactly once and resolves the pending choice`() {
        assertFalse(operations.tryVote(null))
        player.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
        assertTrue(operations.tryVote(null))
        assertTrue(player.civID in game.gameInfo.diplomaticVictoryVotesCast)
        assertNull(game.gameInfo.diplomaticVictoryVotesCast[player.civID])
        assertFalse(operations.tryVote(other.civID))
        assertFalse(player.mayVoteForDiplomaticVictory())
        assertTrue(player.civID in game.gameInfo.clone().diplomaticVictoryVotesCast)
    }

    @Test
    fun `votes recheck timing active player and defeated candidates`() {
        player.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 1)
        assertFalse(operations.tryVote(other.civID))
        player.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
        other.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        val before = json().toJson(game.gameInfo)
        assertFalse(operations.tryVote(other.civID))
        assertEquals(before, json().toJson(game.gameInfo))
        game.gameInfo.currentPlayer = hidden.civID
        game.gameInfo.currentPlayerCiv = hidden
        assertFalse(operations.tryVote(null))
    }

    @Test
    fun `results acknowledgement only dismisses a due result and is idempotent`() {
        player.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 8)
        player.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 1)
        assertFalse(operations.tryAcknowledgeResults())
        player.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 0)
        game.gameInfo.diplomaticVictoryVotesCast[player.civID] = other.civID
        val ballots = game.gameInfo.diplomaticVictoryVotesCast.toMap()
        assertTrue(operations.tryAcknowledgeResults())
        assertFalse(player.shouldShowDiplomaticVotingResults())
        assertFalse(operations.tryAcknowledgeResults())
        assertEquals(ballots, game.gameInfo.diplomaticVictoryVotesCast)
        assertEquals(8, player.getTurnsTillNextDiplomaticVote())
    }

    @Test
    fun `typed result uses native quorum weighting ties and abstention`() {
        capital.cityConstructions.addBuilding("United Nations")
        val ballots = game.gameInfo.diplomaticVictoryVotesCast
        ballots[player.civID] = other.civID
        ballots[hidden.civID] = other.civID
        ballots[other.civID] = null
        val won = player.victoryManager.getDiplomaticVictoryVoteBreakdown()
        assertEquals(3, won.results[other.civID])
        assertEquals(other.civID, won.winnerId)
        assertEquals(other.victoryManager.hasEnoughVotesForDiplomaticVictory(), won.winnerId == other.civID)
        ballots[hidden.civID] = player.civID
        ballots[other.civID] = player.civID
        val tied = player.victoryManager.getDiplomaticVictoryVoteBreakdown()
        assertNull(tied.winnerId)
        assertTrue(tied.winnerText.contains("Tied in first position"))
        ballots.clear()
        assertNull(player.victoryManager.getDiplomaticVictoryVoteBreakdown().winnerId)
    }

    @Test
    fun `a later election can elect a leader without reloading the game`() {
        capital.cityConstructions.addBuilding("United Nations")
        game.gameInfo.diplomaticVictoryVotesCast[player.civID] = null
        game.gameInfo.processDiplomaticVictory()
        assertTrue(game.gameInfo.diplomaticVictoryVotesProcessed)
        assertTrue(game.gameInfo.clone().diplomaticVictoryVotesProcessed)
        player.addFlag(CivFlags.ShouldResetDiplomaticVotes.name, 1)
        com.unciv.logic.civilization.managers.TurnManager(player).startTurn()
        assertTrue(game.gameInfo.diplomaticVictoryVotesCast.isEmpty())
        assertFalse(game.gameInfo.diplomaticVictoryVotesProcessed)
        game.gameInfo.diplomaticVictoryVotesCast[player.civID] = other.civID
        game.gameInfo.diplomaticVictoryVotesCast[hidden.civID] = other.civID
        game.gameInfo.processDiplomaticVictory()
        assertTrue(other.victoryManager.hasEverWonDiplomaticVote)
    }
}
