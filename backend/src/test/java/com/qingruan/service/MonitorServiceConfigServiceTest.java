package com.qingruan.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qingruan.dto.ServiceConfigCreateRequest;
import com.qingruan.dto.ServiceConfigUpdateRequest;
import com.qingruan.entity.MonitorServiceConfig;
import com.qingruan.mapper.MonitorServiceConfigMapper;
import com.qingruan.mapper.ServerInfoMapper;
import com.qingruan.service.impl.MonitorServiceConfigServiceImpl;
import com.qingruan.websocket.client.session.ClientSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorServiceConfigServiceTest {

    @Mock
    private MonitorServiceConfigMapper configMapper;

    @Mock
    private ServerInfoMapper serverInfoMapper;

    @Mock
    private ClientSessionManager clientSessionManager;

    private MonitorServiceConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MonitorServiceConfigServiceImpl(serverInfoMapper, clientSessionManager);
        ReflectionTestUtils.setField(service, "baseMapper", configMapper);
    }

    @Test
    void createNotifiesTargetClientWithPostAndEnabledStatus() {
        when(serverInfoMapper.selectCount(any())).thenReturn(1L);
        when(configMapper.selectCount(any())).thenReturn(0L);
        when(configMapper.insert(any(MonitorServiceConfig.class))).thenAnswer(invocation -> {
            MonitorServiceConfig config = invocation.getArgument(0);
            config.setId(9L);
            return 1;
        });

        MonitorServiceConfig result = service.create(new ServiceConfigCreateRequest(
                "SRV-001", "Nginx", null, 80, 60));

        assertEquals(60, result.getCheckInterval());
        assertEquals(1, result.getStatus());
        assertConfigChangePayload("SRV-001", "POST", 1);
    }

    @Test
    void updateStatusNotifiesTargetClientWithPutAndRequestedStatus() {
        MonitorServiceConfig config = config(9L, "SRV-001", 1);
        when(configMapper.selectById(9L)).thenReturn(config);
        when(configMapper.updateById(any(MonitorServiceConfig.class))).thenReturn(1);

        service.updateStatus(9L, 0);

        assertConfigChangePayload("SRV-001", "PUT", 0);
    }

    @Test
    void deleteNotifiesTargetClientWithDelAndDisabledStatus() {
        MonitorServiceConfig config = config(9L, "SRV-001", 1);
        when(configMapper.selectById(9L)).thenReturn(config);
        when(configMapper.deleteById(9L)).thenReturn(1);

        assertEquals(true, service.delete(9L));

        assertConfigChangePayload("SRV-001", "DEL", 0);
    }

    @Test
    void pageRejectsInvalidPageSizeBeforeDatabaseAccess() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.page(1, 101, null, null, null));

        assertEquals("pageSize必须在1到100之间", exception.getMessage());
    }

    @Test
    void updateStatusRejectsValuesOutsideZeroOrOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(9L, 2));

        assertEquals("status只能为0或1", exception.getMessage());
    }

    @Test
    void updateRequestKeepsAllFieldsOptional() {
        ServiceConfigUpdateRequest request = new ServiceConfigUpdateRequest(null, null, null, null);
        assertEquals(null, request.checkInterval());
    }

    private MonitorServiceConfig config(Long id, String serverCode, Integer status) {
        MonitorServiceConfig config = new MonitorServiceConfig();
        config.setId(id);
        config.setServerCode(serverCode);
        config.setServiceName("Nginx");
        config.setProcessName("nginx");
        config.setPort(80);
        config.setCheckInterval(60);
        config.setStatus(status);
        return config;
    }

    private void assertConfigChangePayload(String expectedServerCode, String expectedActionType,
                                           Integer expectedStatus) {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(clientSessionManager).sendToServer(eq(expectedServerCode), payloadCaptor.capture());

        JSONObject payload = JSONUtil.parseObj(payloadCaptor.getValue());
        assertEquals("MONITOR_CONFIG_CHANGED", payload.getStr("action"));
        assertEquals(expectedServerCode, payload.getStr("server_code"));
        assertEquals(expectedActionType, payload.getStr("action_type"));
        assertEquals(expectedStatus, payload.getInt("status"));
    }
}
