package com.pasarguard.vpn.di

import android.content.Context
import androidx.room.Room
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.data.api.ExponentialRetryInterceptor
import com.pasarguard.vpn.data.api.HwidHeaderInterceptor
import com.pasarguard.vpn.data.api.PasarGuardApi
import com.pasarguard.vpn.data.db.OrderDao
import com.pasarguard.vpn.data.db.PasarGuardDatabase
import com.pasarguard.vpn.data.db.ServerDao
import com.pasarguard.vpn.data.db.SubscriptionDao
import com.pasarguard.vpn.data.db.UsageDao
import com.pasarguard.vpn.data.local.SettingsDataStore
import com.pasarguard.vpn.data.repository.FakePaymentRepository
import com.pasarguard.vpn.data.repository.ServerRepositoryImpl
import com.pasarguard.vpn.data.repository.SubscriptionRepositoryImpl
import com.pasarguard.vpn.domain.repository.AccountActionsRepository
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.vpn.VpnControllerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkAndDbModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(hwidInterceptor: HwidHeaderInterceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(hwidInterceptor)
            .addInterceptor(ExponentialRetryInterceptor())

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun providePasarGuardApi(client: OkHttpClient, json: Json): PasarGuardApi {
        val baseUrl = BrandConfig.defaultBaseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PasarGuardApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PasarGuardDatabase {
        return Room.databaseBuilder(
            context,
            PasarGuardDatabase::class.java,
            "pasarguard_vpn.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideSubscriptionDao(db: PasarGuardDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideServerDao(db: PasarGuardDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideUsageDao(db: PasarGuardDatabase): UsageDao = db.usageDao()

    @Provides
    fun providePingHistoryDao(db: PasarGuardDatabase): com.pasarguard.vpn.data.db.PingHistoryDao =
        db.pingHistoryDao()

    @Provides
    fun provideSubscriptionProfileDao(db: PasarGuardDatabase): com.pasarguard.vpn.data.db.SubscriptionProfileDao =
        db.subscriptionProfileDao()

    @Provides
    fun provideSessionStatDao(db: PasarGuardDatabase): com.pasarguard.vpn.data.db.SessionStatDao =
        db.sessionStatDao()

    @Provides
    fun provideOrderDao(db: PasarGuardDatabase): OrderDao = db.orderDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(
        impl: SubscriptionRepositoryImpl
    ): SubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindServerRepository(
        impl: ServerRepositoryImpl
    ): ServerRepository

    @Binds
    @Singleton
    abstract fun bindPaymentRepository(
        impl: FakePaymentRepository
    ): PaymentRepository

    @Binds
    @Singleton
    abstract fun bindAccountActionsRepository(
        impl: FakePaymentRepository
    ): AccountActionsRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsDataStore
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindVpnController(
        impl: VpnControllerImpl
    ): VpnController
}
