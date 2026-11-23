package com.pasarguard.vpn.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BillingRequestTest {
    @Test
    fun cardPaymentMethodIsIncludedWhenDefaultFieldsAreOmitted() {
        val request = CreateOrderRequestDto("renew_1m_50g", "CARD_TO_CARD")
        val body = Json.encodeToString(CreateOrderRequestDto.serializer(), request)
        val payload = Json.parseToJsonElement(body).jsonObject

        assertEquals("renew_1m_50g", payload["plan_id"]?.jsonPrimitive?.content)
        assertEquals("CARD_TO_CARD", payload["payment_method"]?.jsonPrimitive?.content)
    }
}
