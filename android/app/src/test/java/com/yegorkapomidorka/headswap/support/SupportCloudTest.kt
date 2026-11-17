package com.yegorkapomidorka.headswap.support

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportCloudTest {
    @Test
    fun parseRunQueryResponse() {
        val resp = JSONArray(
            """[{"readTime":"2026-11-04T08:00:00Z"},
               {"document":{"name":"projects/hog-carlink/databases/(default)/documents/requests/abc123",
                 "fields":{"uid":{"stringValue":"u1"},"name":{"stringValue":"صهيب"},"phone":{"stringValue":"079"},
                   "car":{"stringValue":"VW BT 5075 · 20082128"},"app":{"stringValue":"2.6.0-hog.15"},
                   "text":{"stringValue":"لا تظهر الصورة"},"report":{"stringValue":"r"},"status":{"stringValue":"answered"},
                   "createdAt":{"timestampValue":"2026-11-04T07:00:00.123Z"},
                   "reply":{"stringValue":"أعد التشغيل"},"repliedAt":{"timestampValue":"2026-11-04T07:30:00Z"}}}}]""",
        )
        val list = SupportCloud.parseQuery(resp)
        assertEquals(1, list.size)
        val r = list[0]
        assertEquals("abc123", r.id)
        assertEquals("صهيب", r.name)
        assertEquals("answered", r.status)
        assertEquals("2026-11-04T07:00:00.123Z", r.createdAt)
        assertEquals("أعد التشغيل", r.reply)
        assertEquals("", SupportCloud.field(null, "x"))
    }

    @Test
    fun emptyQueryHasNoDocuments() {
        assertEquals(0, SupportCloud.parseQuery(JSONArray("""[{"readTime":"2026-11-04T08:00:00Z"}]""")).size)
    }

    @Test
    fun googleAlreadyLinkedComesAsOkWithoutToken() {
        // так Firebase отвечает на привязку Google, уже связанного с другим аккаунтом (переустановка, второй телефон)
        assertTrue(SupportCloud.alreadyLinked(JSONObject("""{"kind":"identitytoolkit#VerifyAssertionResponse",
            "providerId":"google.com","email":"a@b.c","errorMessage":"FEDERATED_USER_ID_ALREADY_LINKED"}""")))
        assertTrue(SupportCloud.alreadyLinked(JSONObject("""{"needConfirmation":true,"email":"a@b.c"}""")))
        assertFalse(SupportCloud.alreadyLinked(JSONObject("""{"idToken":"t","refreshToken":"r","localId":"u"}""")))
        assertFalse(SupportCloud.alreadyLinked(JSONObject("""{"errorMessage":"INVALID_IDP_RESPONSE"}""")))
    }
}
