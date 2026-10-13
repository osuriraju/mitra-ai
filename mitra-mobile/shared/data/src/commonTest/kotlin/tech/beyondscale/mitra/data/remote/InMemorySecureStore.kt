package tech.beyondscale.mitra.data.remote

class InMemorySecureStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}
