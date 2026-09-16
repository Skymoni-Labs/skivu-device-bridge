package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.json
import ai.nidhoggfgg.phoneuse.server.attachResultIdentity
import ai.nidhoggfgg.phoneuse.server.serializeMcpToolResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolResultSerializationTest {
    private fun screenshot(data: String) = json("available" to true, "mime_type" to "image/png", "data_base64" to data)

    @Test fun executedActionWithoutOptionalObservationIsSuccessfulTextOnly() {
        val result = json("state" to "executed", "action_executed" to true, "request_id" to "once",
            "execution" to json("confirmation" to "android_accepted"))
        val mcp = serializeMcpToolResult(result)
        assertFalse(mcp.getBoolean("isError"))
        assertEquals(1, mcp.getJSONArray("content").length())
        val text = JSONObject(mcp.getJSONArray("content").getJSONObject(0).getString("text"))
        assertTrue(text.getBoolean("action_executed"))
        assertFalse(text.has("observation"))
        assertFalse(text.has("observation_status"))
    }

    @Test fun nestedRecoveryAndRetainedObservationsHaveIdentityAndMcpImagesWithoutChangingCache() {
        val last = json("observation_id" to "old", "screenshot" to screenshot("original-frame"))
        val recovery = json("observation_status" to "failed", "last_observation" to last,
            "error" to json("code" to "TIMEOUT"))
        val retained = json("state" to "failed", "action_executed" to false, "recovery_observation" to recovery)
        val result = attachResultIdentity(json("request" to retained), "device", "service")
        assertEquals("device", last.getString("device_id"))
        assertEquals("service", recovery.getString("service_instance_id"))
        val mcp = serializeMcpToolResult(result)
        assertTrue(mcp.getBoolean("isError"))
        val blocks = mcp.getJSONArray("content")
        assertEquals(2, blocks.length())
        assertEquals("original-frame", blocks.getJSONObject(0).getString("data"))
        val text = JSONObject(blocks.getJSONObject(1).getString("text"))
        assertFalse(text.getJSONObject("request").getJSONObject("recovery_observation").getJSONObject("last_observation")
            .getJSONObject("screenshot").has("data_base64"))
        assertEquals("original-frame", last.getJSONObject("screenshot").getString("data_base64"))
    }

    @Test fun executedInputWithUnmetConditionIsAnIncompleteMcpToolResult() {
        val result = json("state" to "executed", "action_executed" to true, "observation" to
            json("observation_status" to "condition_timeout", "condition" to json("status" to "ambiguous", "timed_out" to true),
                "screenshot" to screenshot("frame")))
        val mcp = serializeMcpToolResult(result)
        assertTrue(mcp.getBoolean("isError"))
        val text = JSONObject(mcp.getJSONArray("content").getJSONObject(1).getString("text"))
        assertTrue(text.getBoolean("action_executed"))
        assertEquals("executed", text.getString("state"))
        assertFalse(serializeMcpToolResult(json("observation_status" to "complete",
            "condition" to json("status" to "satisfied", "timed_out" to false))).getBoolean("isError"))
    }
    @Test fun requestedChannelFailureIsIncompleteButUnrequestedChannelsAreIgnored() {
        assertTrue(serializeMcpToolResult(json("observation_status" to "complete", "screenshot" to json("available" to false),
            "tree" to json("available" to true))).getBoolean("isError"))
        assertTrue(serializeMcpToolResult(json("observation_status" to "complete", "tree" to json("available" to false))).getBoolean("isError"))
        assertFalse(serializeMcpToolResult(json("observation_status" to "complete", "screenshot" to null,
            "tree" to json("available" to true))).getBoolean("isError"))
    }
}
