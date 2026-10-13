package tech.beyondscale.mitra.core

import kotlin.uuid.Uuid

/** Client-generated ids for optimistic creates — the API validates them as UUIDs. */
fun newId(): String = Uuid.random().toString()
