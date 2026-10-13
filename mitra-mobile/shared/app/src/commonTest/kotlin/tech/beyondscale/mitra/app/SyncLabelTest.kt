package tech.beyondscale.mitra.app

import tech.beyondscale.mitra.designsystem.components.PillTone
import tech.beyondscale.mitra.domain.model.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncLabelTest {
    @Test fun labels() {
        assertNull(syncLabel(SyncStatus.Idle))
        assertEquals("Syncing…" to PillTone.Accent, syncLabel(SyncStatus.Syncing(2)))
        assertEquals("Offline · 3 pending" to PillTone.Warning, syncLabel(SyncStatus.Offline(3)))
        assertEquals("Waiting to sync · 1" to PillTone.Neutral, syncLabel(SyncStatus.Retrying(1)))
    }
}
