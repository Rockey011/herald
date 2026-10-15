package dev.hermeskotlin.core.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BargeInTest {

    /** A [BargeIn] fed 20 ms frames, the clock carried from one stretch to the next. */
    private class Mic(val bargeIn: BargeIn = BargeIn()) {
        var now = 0L

        /** Feeds [ms] of [level]; when it tripped or finished in that stretch, and which, or null. */
        fun feed(level: Float, ms: Long, playing: Boolean = false): Pair<Long, BargeIn.Step>? {
            val until = now + ms
            while (now < until) {
                now += 20
                val step = bargeIn.onFrame(level, now, playing)
                if (step == BargeIn.Step.Tripped || step == BargeIn.Step.Done) return now to step
            }
            return null
        }
    }

    @Test
    fun speechThatHoldsCutsIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        val (at, step) = assertNotNull(mic.feed(0.2f, 1_000))
        assertEquals(BargeIn.Step.Tripped, step)
        // Most of 300 ms of it, not the first frame.
        assertTrue(at - 1_000 in 240L..340L, "tripped ${at - 1_000} ms in")
    }

    @Test
    fun aBlipDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.3f, 120))
        assertNull(mic.feed(0.01f, 2_000))
    }

    @Test
    fun theVoicesBleedAloneDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        // Over the quiet room's bar, under the one while the voice plays.
        assertNull(mic.feed(0.07f, 5_000, playing = true))
        assertNotNull(mic.feed(0.2f, 1_000, playing = true))
    }

    @Test
    fun theVoicesFirstMomentDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        // The voice starting is loud for a moment, inside the grace.
        assertNull(mic.feed(0.2f, 400, playing = true))
        assertNull(mic.feed(0.02f, 1_000, playing = true))
    }

    @Test
    fun theFloorIsNotMeasuredOverTheVoice() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.08f, 5_000, playing = true))
        // Had the bleed lifted the floor, quiet speech after the voice stopped would be out of reach.
        assertNull(mic.feed(0.01f, 300))
        assertNotNull(mic.feed(0.06f, 1_000))
    }

    @Test
    fun speechStaysWithinReachInALoudRoom() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.1f, 1_000))
        assertEquals(BargeIn.TRIGGER_CEILING_LEVEL, bargeIn.trigger(playing = true))
        assertNotNull(mic.feed(0.3f, 1_000, playing = true))
    }

    @Test
    fun aLowerMultiplierCutsInSooner() {
        val sensitive = Mic(BargeIn(BargeInSettings(multiplier = 1.5)))
        assertNull(sensitive.feed(0.01f, 1_000))
        assertNotNull(sensitive.feed(0.07f, 2_000, playing = true))

        val stock = Mic(BargeIn(BargeInSettings(multiplier = BargeIn.DEFAULT_MULTIPLIER)))
        assertNull(stock.feed(0.01f, 1_000))
        assertNull(stock.feed(0.07f, 2_000, playing = true))
    }

    @Test
    fun theUtteranceEndsOnAPause() {
        val mic = Mic(BargeIn(silenceMs = 1_000))
        assertNull(mic.feed(0.01f, 1_000))
        assertNotNull(mic.feed(0.2f, 400))
        // Words with short gaps between them carry on.
        assertNull(mic.feed(0.2f, 1_500))
        assertNull(mic.feed(0.01f, 500))
        assertNull(mic.feed(0.2f, 500))
        val trippedAt = mic.now
        val (at, step) = assertNotNull(mic.feed(0.01f, 3_000))
        assertEquals(BargeIn.Step.Done, step)
        // A second from the first quiet frame.
        assertEquals(1_020L, at - trippedAt)
    }

    @Test
    fun aRatioMatchesPythons() {
        fun ratio(a: String, b: String) = sequenceRatio(a.toList(), b.toList())
        assertClose(0.7857142857142857, ratio("hello world", "hello there world"))
        assertClose(0.8636363636363636, ratio("the quick brown fox", "the quick brown fox jumps"))
        assertClose(0.0, ratio("abc", ""))
        assertClose(1.0, ratio("", ""))
        // Past 200 characters, the commonest ones can't start a match (difflib's autojunk).
        assertClose(0.10223642172523961, ratio("aaab the weather", "a".repeat(150) + "b".repeat(120) + " the weather today is sunny"))
    }

    @Test
    fun itsOwnVoiceIsAnEcho() {
        val reply = "The build finished in four minutes and every test passed. Do you want me to deploy it now?"
        assertTrue(isTtsEcho("The build finished in four minutes", reply))
        assertTrue(isTtsEcho("every test passed do you want", reply))
        assertTrue(isTtsEcho("the build finished in 4 minutes and every test passed. do you want me to deploy it now", reply))
    }

    @Test
    fun someoneCuttingInIsNotAnEcho() {
        val reply = "The build finished in four minutes and every test passed. Do you want me to deploy it now?"
        assertFalse(isTtsEcho("Wait, check the staging logs first", reply))
        assertFalse(isTtsEcho("yes", reply))
        assertFalse(isTtsEcho("no", reply))
        assertFalse(isTtsEcho("anything", ""))
    }

    private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-12, "expected $expected, was $actual")
}
