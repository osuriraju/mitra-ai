package tech.beyondscale.mitra.domain.model

data class User(
    val id: String,
    val name: String,
    val email: String,
    val username: String?,
    val phone: String?,
    val settings: Settings,
    val onboardedAt: String?,
    val memberSince: String,
) {
    val firstName: String get() = name.trim().split(" ").first()
    val initials: String
        get() = name.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}
