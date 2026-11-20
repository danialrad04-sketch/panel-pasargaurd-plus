package com.pasarguard.vpn.data.api

import com.pasarguard.vpn.data.api.dto.PasarGuardRawLinksDto
import com.pasarguard.vpn.data.api.dto.PasarGuardSubInfoDto
import com.pasarguard.vpn.data.api.dto.PasarGuardSubUsageDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.Url

/**
 * Single source of truth for PasarGuard public customer subscription endpoints:
 * - GET  {base}/sub/{token}/info  -> User quota, status, expiry, reset strategy, HWID limit
 * - GET  {base}/sub/{token}/raw   -> JSON array of proxy URIs (VLESS, VMess, Trojan, SS)
 * - GET  {base}/sub/{token}       -> Base64-encoded proxy URIs + `subscription-userinfo` header
 * - GET  {base}/sub/{token}/usage -> Historical usage buckets for chart rendering
 * - HEAD {base}/sub/{token}       -> Lightweight usage header check
 */
interface PasarGuardApi {

    @GET
    suspend fun getSubscriptionInfo(@Url url: String): Response<PasarGuardSubInfoDto>

    @GET
    suspend fun getRawLinks(@Url url: String): Response<PasarGuardRawLinksDto>

    @GET
    suspend fun getBase64Subscription(@Url url: String): Response<ResponseBody>

    @GET
    suspend fun getSubscriptionUsage(@Url url: String): Response<PasarGuardSubUsageDto>

    @HEAD
    suspend fun headSubscription(@Url url: String): Response<Void>
}
