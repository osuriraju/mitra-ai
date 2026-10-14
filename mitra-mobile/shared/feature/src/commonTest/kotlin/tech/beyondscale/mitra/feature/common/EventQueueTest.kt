package tech.beyondscale.mitra.feature.common

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventQueueTest {
    @Test fun eventsSentBeforeCollectionAreDeliveredInOrder() = runTest {
        val q = EventQueue<String>()
        q.send(UiEvent.Snack("a"))
        q.send(UiEvent.Haptic(HapticKind.Confirm))
        q.send(UiEvent.Close)
        assertEquals(
            listOf<UiEvent<String>>(UiEvent.Snack("a"), UiEvent.Haptic(HapticKind.Confirm), UiEvent.Close),
            q.events.take(3).toList(),
        )
    }
}
