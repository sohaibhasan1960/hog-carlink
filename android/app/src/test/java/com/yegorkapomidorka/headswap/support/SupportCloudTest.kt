package com.yegorkapomidorka.headswap.support

import org.json.JSONArray
import org.junit.Assert.assertEquals
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
}
