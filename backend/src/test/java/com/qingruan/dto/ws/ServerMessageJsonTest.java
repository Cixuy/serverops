package com.qingruan.dto.ws;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMessageJsonTest {

    @Test
    void configChangeMessageUsesWireFieldNamesAndNumericStatus() {
        String json = JSONUtil.toJsonStr(ServerMessage.builder()
                .type("monitor_config_changed")
                .serverCode("SRV-001")
                .status(1)
                .actionType("PUT")
                .build());

        assertTrue(json.contains("\"action_type\":\"PUT\""));
        assertTrue(json.contains("\"status\":1"));
    }
}