package tech.beyondscale.mitra.android

import java.net.URI

/**
 * Fails fast on a missing or unusable API URL instead of surfacing as confusing network errors later.
 * Release builds require https; debug builds may use http (the debug network security config limits which hosts).
 */
internal fun checkApiUrl(url: String, allowCleartext: Boolean) {
    require(url.isNotBlank()) { "API URL missing: pass -Pmitra.releaseApiUrl=https://…/api/ for release builds" }
    val uri = runCatching { URI(url) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    require(uri != null && !uri.host.isNullOrBlank()) { "API URL has no host: $url" }
    require(scheme == "https" || (allowCleartext && scheme == "http")) {
        "API URL must use https${if (allowCleartext) " or http" else ""}: $url"
    }
}
