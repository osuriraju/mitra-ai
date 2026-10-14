package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.data.local.aiProposalFromJson
import tech.beyondscale.mitra.data.local.revertOpsFromJson
import tech.beyondscale.mitra.data.local.suggestionActionFromJson
import tech.beyondscale.mitra.data.local.toJson
import tech.beyondscale.mitra.domain.model.ActivityItem
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiReply
import tech.beyondscale.mitra.domain.model.JournalDraft
import tech.beyondscale.mitra.domain.model.Suggestion

/* Shapes of mitra-backend/src/ai (ai.service.ts `actionOut`, `ask`, `suggestions`) and src/activity. */

@Serializable
data class AiStatusDto(val enabled: Boolean = false)

/** `POST ai/ask` reply. An unreadable chart/proposal is dropped rather than failing the whole reply. */
@Serializable
data class AiReplyDto(val text: String = "", val chart: JsonElement? = null, val proposal: JsonObject? = null) {
    fun toDomain() = AiReply(
        text,
        (chart as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.isNotEmpty() },
        proposal?.let(::aiProposalFromJson),
    )
}

@Serializable
data class SuggestionDto(
    val id: String,
    val ic: String = "sparkles",
    val t: String = "",
    val p: String = "",
    val src: String = "",
    val tone: String = "",
    val preview: String? = null,
    val action: JsonObject? = null,
) {
    fun toDomain() = Suggestion(id, ic, t, p, src, tone, preview, action?.let(::suggestionActionFromJson))
}

/** One audit-log row; the server's `when` label is ignored (computed on device from [at]). */
@Serializable
data class AiActionDto(
    val id: String,
    val at: String,
    val action: String,
    val detail: String = "",
    val state: String = "",
    val tone: String = "",
    val revertible: Boolean = false,
    val reverted: Boolean = false,
    val revert: JsonElement? = null,
) {
    fun toDomain() = AiAction(id, at, action, detail, state, tone, revertible, reverted, revertOpsFromJson(revert))
}

@Serializable
data class JournalDraftDto(val text: String = "", val sources: String = "") {
    fun toDomain() = JournalDraft(text, sources)
}

@Serializable
data class ActivityDto(
    val id: String,
    val text: String,
    val icon: String = "circle",
    val tone: String = "",
    val module: String? = null,
    val entityId: String? = null,
    val at: String,
) {
    fun toDomain() = ActivityItem(id, text, icon, tone, module, entityId, at)
}

/** `POST ai/actions` body. [id] is client-generated once and kept in the outbox row, so a retry is idempotent. */
fun AiActionDraft.createBody(id: String): JsonObject = buildJsonObject {
    put("id", id)
    put("action", action.trim().take(80))
    put("detail", detail.take(500))
    put("state", state.take(60))
    put("tone", tone.take(20))
    put("revertible", revertible)
    if (revert.isNotEmpty()) put("revert", revert.take(50).toJson())
}
