package tech.beyondscale.mitra.data.remote

/** Small encrypted key/value store for secrets (session cookie). Android: Keystore-backed; iOS later: Keychain. */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
