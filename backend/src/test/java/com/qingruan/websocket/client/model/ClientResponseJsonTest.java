package com.qingruan.websocket.client.model;

import com.qingruan.websocket.common.util.WsUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientResponseJsonTest {

    @Test
    void configChangeUsesServerCodeActionTypeAndNumericStatus() {
        String json = WsUtil.toJson(ClientResponse.builder()
                .action("MONITOR_CONFIG_CHANGED")
                .serverCode("SRV-001")
                .status(1)
                .actionType("PUT")
                .data(List.of())
                .build());

        assertTrue(json.contains("\"server_code\":\"SRV-001\""));
        assertTrue(json.contains("\"action_type\":\"PUT\""));
        assertTrue(json.contains("\"status\":1"));
    }
}
