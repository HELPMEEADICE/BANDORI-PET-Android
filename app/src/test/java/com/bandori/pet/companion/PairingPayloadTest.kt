package com.bandori.pet.companion

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun parsesVersionedPairingUri() {
        val json = JSONObject()
            .put("v", 1)
            .put("instanceId", "desktop-id")
            .put("name", "Desktop")
            .put("hosts", JSONArray().put("192.168.1.4").put("fd00::4"))
            .put("port", 38474)
            .put("pinSha256", "pin")
            .put("token", "token")
            .put("expiresAt", System.currentTimeMillis() + 120_000)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))

        val parsed = PairingPayload.parse("bandoripet://pair?data=$encoded")

        assertEquals(listOf("192.168.1.4", "fd00::4"), parsed.hosts)
        assertEquals("wss://[fd00::4]:38474/v1/ws", parsed.endpoint("fd00::4"))
    }

    @Test
    fun rejectsExpiredPairingUri() {
        val json = JSONObject()
            .put("v", 1)
            .put("instanceId", "desktop-id")
            .put("hosts", JSONArray().put("127.0.0.1"))
            .put("port", 38474)
            .put("pinSha256", "pin")
            .put("token", "token")
            .put("expiresAt", 1)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))

        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse("bandoripet://pair?data=$encoded")
        }
    }
}
