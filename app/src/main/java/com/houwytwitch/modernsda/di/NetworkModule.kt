package com.houwytwitch.modernsda.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.houwytwitch.modernsda.domain.steam.AvatarFetcher
import com.houwytwitch.modernsda.domain.steam.SteamConfirmations
import com.houwytwitch.modernsda.domain.steam.SteamLogin
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()

    @Provides
    @Singleton
    fun provideAvatarFetcher(
        okHttpClient: OkHttpClient,
        gson: Gson,
    ): AvatarFetcher = AvatarFetcher(okHttpClient, gson)

    @Provides
    @Singleton
    fun provideSteamConfirmations(
        okHttpClient: OkHttpClient,
        gson: Gson,
    ): SteamConfirmations = SteamConfirmations(okHttpClient, gson)

    @Provides
    @Singleton
    fun provideSteamLogin(
        okHttpClient: OkHttpClient,
        gson: Gson,
    ): SteamLogin = SteamLogin(okHttpClient, gson)
}
