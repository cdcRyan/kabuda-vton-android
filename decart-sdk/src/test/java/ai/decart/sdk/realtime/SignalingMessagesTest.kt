package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingMessagesTest {
    @Test
    fun `parse session_id without legacy server fields`() {
        val message = SignalingMessageParser.parse(
            """{"type":"session_id","session_id":"session-123"}""",
        ) as SessionIdMessage

        assertEquals("session-123", message.sessionId)
        assertEquals(null, message.serverIp)
        assertEquals(null, message.serverPort)
    }
}
