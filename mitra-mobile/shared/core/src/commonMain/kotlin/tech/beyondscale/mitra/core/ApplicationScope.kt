package tech.beyondscale.mitra.core

import kotlinx.coroutines.CoroutineScope

/** A process-lifetime scope for work that must outlive a screen (e.g. saving a draft as it closes). */
class ApplicationScope(val scope: CoroutineScope)
