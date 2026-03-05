package com.houwytwitch.modernsda.data.repository

import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.data.model.Confirmation
import com.houwytwitch.modernsda.data.model.ConfirmationResult
import com.houwytwitch.modernsda.domain.steam.SteamConfirmations
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfirmationRepository @Inject constructor(
    private val steamConfirmations: SteamConfirmations,
) {
    suspend fun fetchConfirmations(account: Account): Result<List<Confirmation>> {
        if (account.sessionId.isBlank() || account.steamLoginSecure.isBlank()) {
            return Result.failure(Exception("No session data. Re-import your .mafile with a valid session."))
        }

        return steamConfirmations.fetchConfirmations(
            steamId = account.steamId,
            identitySecret = account.identitySecret,
            sessionId = account.sessionId,
            steamLoginSecure = account.steamLoginSecure,
        )
    }

    suspend fun acceptConfirmation(
        account: Account,
        confirmation: Confirmation,
    ): ConfirmationResult = steamConfirmations.respondToConfirmation(
        steamId = account.steamId,
        identitySecret = account.identitySecret,
        sessionId = account.sessionId,
        steamLoginSecure = account.steamLoginSecure,
        confirmation = confirmation,
        accept = true,
    )

    suspend fun declineConfirmation(
        account: Account,
        confirmation: Confirmation,
    ): ConfirmationResult = steamConfirmations.respondToConfirmation(
        steamId = account.steamId,
        identitySecret = account.identitySecret,
        sessionId = account.sessionId,
        steamLoginSecure = account.steamLoginSecure,
        confirmation = confirmation,
        accept = false,
    )

    suspend fun acceptAllConfirmations(
        account: Account,
        confirmations: List<Confirmation>,
    ): ConfirmationResult = steamConfirmations.acceptAllConfirmations(
        steamId = account.steamId,
        identitySecret = account.identitySecret,
        sessionId = account.sessionId,
        steamLoginSecure = account.steamLoginSecure,
        confirmations = confirmations,
    )
}
