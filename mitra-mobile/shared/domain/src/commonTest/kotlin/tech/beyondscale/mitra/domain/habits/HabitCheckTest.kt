package tech.beyondscale.mitra.domain.habits

import tech.beyondscale.mitra.core.addDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitCheckTest {
    private val readBinary = habit("r", "Read")

    @Test fun binaryTapShowsNextStreak() {
        val t = habitTap(readBinary.copy(logs = lastDays(3)), TODAY)
        assertEquals(HabitTap("r", TODAY, 1, "Read ✓ · streak 4", undoValue = null, canUndo = true, reachedTarget = true, streakMilestone = false), t)
        assertEquals("Read ✓", habitTap(readBinary, TODAY).snack)
    }

    @Test fun doneTapUnchecks() {
        val t = habitTap(readBinary.copy(logs = mapOf(TODAY to 1)), TODAY)
        assertNull(t.value)
        assertEquals("Unchecked · Read", t.snack)
        assertFalse(t.canUndo)
        assertFalse(t.reachedTarget)
        assertFalse(t.streakMilestone)
    }

    @Test fun durationStepsByHalfTarget() {
        val stretch = habit("s", "Stretch", tech.beyondscale.mitra.domain.model.HabitType.Duration, 20, "min")
        val first = habitTap(stretch, TODAY)
        assertEquals(10, first.value)
        assertNull(first.snack)
        assertFalse(first.canUndo)
        assertFalse(first.reachedTarget)
        val second = habitTap(stretch.copy(logs = mapOf(TODAY to 10)), TODAY)
        assertEquals(20, second.value)
        assertEquals("Stretch ✓ · target reached", second.snack)
        assertFalse(second.canUndo)
        assertTrue(second.reachedTarget)
        // never overshoots the target
        assertEquals(20, habitTap(stretch.copy(logs = mapOf(TODAY to 15)), TODAY).value)
        // count habits step by one
        assertEquals(6, habitTap(drinkWater.copy(logs = mapOf(TODAY to 5)), TODAY).value)
    }

    @Test fun streakMilestoneAtSeven() {
        assertTrue(habitTap(readBinary.copy(logs = lastDays(6)), TODAY).streakMilestone)
        assertFalse(habitTap(readBinary.copy(logs = lastDays(7)), TODAY).streakMilestone)
        assertTrue(habitTap(readBinary.copy(logs = lastDays(29)), TODAY).streakMilestone)
        // a measurable step that does not reach the target is no milestone
        val walk = morningWalk.copy(logs = lastDays(6, value = 30))
        assertFalse(habitTap(walk, TODAY).streakMilestone)
        assertTrue(habitTap(walk.copy(logs = walk.logs + (TODAY to 20)), TODAY).streakMilestone)
        assertEquals(setOf(7, 30, 100, 365), STREAK_MILESTONES)
    }

    @Test fun completeLogsTargetWithUndoToPrevious() {
        val water = drinkWater.copy(logs = mapOf(TODAY to 5))
        assertEquals(HabitTap("h-water", TODAY, 8, "Drink water ✓", undoValue = 5, canUndo = true, reachedTarget = true, streakMilestone = false), habitComplete(water, TODAY))
        val fresh = habitComplete(readBinary.copy(logs = lastDays(6)), TODAY)
        assertEquals(1, fresh.value)
        assertNull(fresh.undoValue)
        assertTrue(fresh.streakMilestone)
    }

    @Test fun completeIsANoOpWhenAlreadyDone() {
        val already = habitComplete(readBinary.copy(logs = mapOf(TODAY to 1, addDays(TODAY, -1) to 1)), TODAY)
        assertEquals(HabitTap("r", TODAY, 1, snack = null, undoValue = 1, canUndo = false, reachedTarget = false, streakMilestone = false), already)
        // an over-target day is never lowered back to the target
        val over = habitComplete(drinkWater.copy(logs = mapOf(TODAY to 10)), TODAY)
        assertEquals(10, over.value)
        assertNull(over.snack)
        assertFalse(over.canUndo)
    }
}
