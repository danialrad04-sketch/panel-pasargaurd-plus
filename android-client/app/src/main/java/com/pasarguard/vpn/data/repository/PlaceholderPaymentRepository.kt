package com.pasarguard.vpn.data.repository

import android.net.Uri
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.CheckoutSession
import com.pasarguard.vpn.domain.model.RenewalPlan
import com.pasarguard.vpn.domain.repository.PaymentRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clean placeholder implementation of [PaymentRepository].
 *
 * To connect your real payment gateway (Zarinpal, Cryptomus, Telegram Bot storefront,
 * WooCommerce, or a custom PasarGuard billing microservice):
 * 1. Inject Retrofit/OkHttp into your implementation class.
 * 2. Return live plans from your billing API in [getAvailablePlans].
 * 3. Request a checkout redirect URL in [createCheckoutSession], passing [callbackUrl]
 *    (`pasarguardvpn://payment/callback`) as your gateway's return URL.
 * 4. Bind your implementation in [com.pasarguard.vpn.di.RepositoryModule].
 */
@Singleton
class PlaceholderPaymentRepository @Inject constructor() : PaymentRepository {

    override suspend fun getAvailablePlans(): Result<List<RenewalPlan>> {
        val plans = listOf(
            RenewalPlan(
                id = "plan_1m_50g",
                titleEn = "1 Month • 50 GB",
                titleFa = "یک‌ماهه • ۵۰ گیگابایت",
                durationDays = 30,
                dataLimitGb = 50,
                priceDisplayEn = "$4.90",
                priceDisplayFa = "۱۸۰,۰۰۰ تومان",
                isPopular = false
            ),
            RenewalPlan(
                id = "plan_3m_150g",
                titleEn = "3 Months • 150 GB",
                titleFa = "سه‌ماهه • ۱۵۰ گیگابایت",
                durationDays = 90,
                dataLimitGb = 150,
                priceDisplayEn = "$12.90",
                priceDisplayFa = "۴۹۰,۰۰۰ تومان",
                isPopular = true,
                badgeEn = "Save 15%",
                badgeFa = "۱۵٪ تخفیف"
            ),
            RenewalPlan(
                id = "plan_6m_unlimited",
                titleEn = "6 Months • Unlimited",
                titleFa = "شش‌ماهه • حجم نامحدود",
                durationDays = 180,
                dataLimitGb = null,
                priceDisplayEn = "$24.00",
                priceDisplayFa = "۸۹۰,۰۰۰ تومان",
                isPopular = false,
                badgeEn = "Best Value",
                badgeFa = "به‌صرفه‌ترین"
            )
        )
        return Result.success(plans)
    }

    override suspend fun createCheckoutSession(
        planId: String,
        subscriptionToken: String,
        callbackUrl: String
    ): Result<CheckoutSession> {
        val refId = "PG-${UUID.randomUUID().toString().take(8).uppercase()}"
        val checkoutUri = Uri.parse("${BrandConfig.defaultBaseUrl}/billing/checkout")
            .buildUpon()
            .appendQueryParameter("plan", planId)
            .appendQueryParameter("sub", subscriptionToken)
            .appendQueryParameter("ref", refId)
            .appendQueryParameter("return_url", callbackUrl)
            .build()
            .toString()

        return Result.success(
            CheckoutSession(
                referenceId = refId,
                paymentUrl = checkoutUri,
                planId = planId
            )
        )
    }
}
