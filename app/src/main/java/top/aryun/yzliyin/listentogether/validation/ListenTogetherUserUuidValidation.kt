package top.aryun.yzliyin.listentogether.validation

import top.aryun.yzliyin.R
import top.aryun.yzliyin.listentogether.LtMessages

private val USER_UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

fun validateListenTogetherUserUuid(userUuid: String): ListenTogetherValidationError? {
    val normalized = userUuid.trim()
    return when {
        normalized.isBlank() -> {
            ListenTogetherValidationError(
                messageFormat = LtMessages.listen_together_error_user_uuid_required,
                messageRes = R.string.ltm_validate_user_uuid_required
            )
        }

        !USER_UUID_REGEX.matches(normalized) -> {
            ListenTogetherValidationError(
                messageFormat = LtMessages.listen_together_error_user_uuid_invalid,
                messageRes = R.string.ltm_validate_user_uuid_invalid
            )
        }

        else -> null
    }
}

fun requireValidListenTogetherUserUuid(userUuid: String): String {
    val normalized = userUuid.trim()
    validateListenTogetherUserUuid(normalized)?.let { error(it.formatForApp()) }
    return normalized.lowercase()
}
