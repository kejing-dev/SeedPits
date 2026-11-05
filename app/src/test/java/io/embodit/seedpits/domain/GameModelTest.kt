package io.embodit.seedpits.domain

import io.embodit.seedpits.domain.engine.Level
import io.embodit.seedpits.domain.game.FirstMover
import io.embodit.seedpits.domain.game.GameConfig
import io.embodit.seedpits.domain.game.SaveCodec
import io.embodit.seedpits.domain.game.SavedGame
import io.embodit.seedpits.domain.game.UndoPlan
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameModelTest {
    @Test fun saveRoundTrip() {
        var p = Kalah.initial(Side.NORTH)
        val moves = ArrayList<Int>()
        repeat(5) { val m = Kalah.legalMoves(p).last(); moves += m; p = Kalah.sow(p, m).after }
        val g = SavedGame(GameConfig(Level.MASTER, FirstMover.RANDOM), Kalah.initial(Side.NORTH), moves, 12345L, 2, true, 99L)
        val back = SaveCodec.decode(SaveCodec.encode(g))
        assertEquals(g, back)
    }

    @Test fun corruptSaveRejected() {
        assertNull(SaveCodec.decode("v=1\nlevel=3\nstart=4,4,4,4,4,4,0,4,4,4,4,4,4,0/S\nmoves=7\n"))
        assertNull(SaveCodec.decode("garbage"))
    }

    @Test fun undoGoesBackToStartOfPlayersTurnIncludingChainAndAi() {
        val start = Kalah.initial(Side.SOUTH)
        // c (2) lands in the store: extra turn; then f; then AI plays A.
        val moves = listOf(2, 5, 7)
        // Player's turn started at 0; after the AI move it's the player's turn again.
        assertEquals(0, UndoPlan.target(start, moves))
        assertNull(UndoPlan.target(Kalah.initial(Side.NORTH), listOf(9)))
    }
}
