package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class GreatPersonThresholdTest {
    private val testGame = TestGame()
    private val player = testGame.addCiv()

    @Test
    fun `reading default thresholds does not initialize a saved pool`() {
        val before = json().toJson(testGame.gameInfo)
        val expected = (100 * testGame.gameInfo.speed.modifier).toInt()
        assertEquals(expected, player.greatPeople.getPointsRequiredForGreatPerson("Great Scientist"))
        assertEquals(expected, player.greatPeople.getPointsRequiredForGreatPerson("Great Engineer"))
        assertTrue(player.greatPeople.pointsForNextGreatPersonCounter.isEmpty())
        assertEquals(before, json().toJson(testGame.gameInfo))
    }

    @Test
    fun `first generation doubles the default pool without a previous threshold query`() {
        val first = (100 * testGame.gameInfo.speed.modifier).toInt()
        player.greatPeople.greatPersonPointsCounter["Great Scientist"] = first + 7
        assertEquals("Great Scientist", player.greatPeople.getNewGreatPerson())
        assertEquals(7, player.greatPeople.greatPersonPointsCounter["Great Scientist"])
        assertEquals((200 * testGame.gameInfo.speed.modifier).toInt(), player.greatPeople.getPointsRequiredForGreatPerson("Great Scientist"))
        assertNull(player.greatPeople.getNewGreatPerson())
    }
}
