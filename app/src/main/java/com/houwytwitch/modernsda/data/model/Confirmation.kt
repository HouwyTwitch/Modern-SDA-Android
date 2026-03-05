package com.houwytwitch.modernsda.data.model

/**
 * A pending Steam mobile confirmation (trade, market listing, etc.).
 */
data class Confirmation(
    val id: String,
    val nonce: String,
    val creatorId: Long,
    val type: ConfirmationType,
    val headline: String,
    val summary: String,
    val icon: String = "",
)

enum class ConfirmationType(val displayName: String) {
    TRADE("Trade"),
    MARKET_LISTING("Market Listing"),
    ACCOUNT("Account"),
    PHONE("Phone"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        fun fromInt(value: Int): ConfirmationType = when (value) {
            2 -> TRADE
            3 -> MARKET_LISTING
            6 -> ACCOUNT
            7 -> PHONE
            else -> UNKNOWN
        }
    }
}

sealed class ConfirmationResult {
    data object Success : ConfirmationResult()
    data class Error(val message: String) : ConfirmationResult()
}
