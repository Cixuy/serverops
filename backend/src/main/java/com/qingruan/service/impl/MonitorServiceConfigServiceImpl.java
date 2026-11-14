package com.qingruan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.dto.response.AgentMonitorConfig;
import com.qingruan.common.result.PageResult;
import com.qingruan.dto.ServiceConfigCreateRequest;
import com.qingruan.dto.ServiceConfigUpdateRequest;
import com.qingruan.entity.MonitorServiceConfig;
import com.qingruan.websocket.client.model.ClientMonitorConfig;
import com.qingruan.websocket.client.model.ClientResponse;
import com.qingruan.websocket.client.session.ClientSessionManager;
import com.qingruan.websocket.common.util.WsUtil;
import com.qingruan.exception.ServiceConfigConflictException;
import com.qingruan.mapper.MonitorServiceConfigMapper;
import com.qingruan.mapper.ServerInfoMapper;
import com.qingruan.service.MonitorServiceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MonitorServiceConfigServiceImpl extends ServiceImpl<MonitorServiceConfigMapper, MonitorServiceConfig>
        implements MonitorServiceConfigService {

    private static final int DEFAULT_CHECK_INTERVAL = 60;
    private static final int DEFAULT_STATUS = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ServerInfoMapper serverInfoMapper;
    private final ClientSessionManager clientSessionManager;

    @Override
    public PageResult<MonitorServiceConfig> page(Integer pageNum, Integer pageSize,
                                                  String serverCode, String keyword, Integer status) {
        int currentPage = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? 10 : pageSize;
        if (currentPage < 1) {
            throw new IllegalArgumentException("pageNum必须大于0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize必须在1到100之间");
        }
        if (status != null && status != 0 && status != 1) {
            throw new IllegalArgumentException("status只能为0或1");
        }

        LambdaQueryWrapper<MonitorServiceConfig> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(serverCode)) {
            query.eq(MonitorServiceConfig::getServerCode, serverCode.trim());
        }
        if (StringUtils.hasText(keyword)) {
            query.like(MonitorServiceConfig::getServiceName, keyword.trim());
        }
        if (status != null) {
            query.eq(MonitorServiceConfig::getStatus, status);
        }
        query.orderByDesc(MonitorServiceConfig::getCreatedAt);

        IPage<MonitorServiceConfig> result = page(new Page<>(currentPage, size), query);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public MonitorServiceConfig detail(Long id) {
        requireId(id);
        MonitorServiceConfig config = getById(id);
        if (config == null) {
            throw new IllegalStateException("服务配置不存在");
        }
        return config;
    }

    @Override
    public MonitorServiceConfig create(ServiceConfigCreateRequest request) {
        String serverCode = requireText(request.serverCode(), "serverCode不能为空");
        String serviceName = requireText(request.serviceName(), "serviceName不能为空");
        ensureServerExists(serverCode);
        ensureUnique(serverCode, serviceName, null);

        MonitorServiceConfig config = new MonitorServiceConfig();
        config.setServerCode(serverCode);
        config.setServiceName(serviceName);
        config.setProcessName(trimToNull(request.processName()));
        config.setPort(request.port());
        config.setCheckInterval(request.effectiveCheckInterval());
        config.setStatus(request.effectiveStatus());
        save(config);
        notifyClientConfigChanged(config, "POST");
        return config;
    }

    @Override
    public MonitorServiceConfig update(Long id, ServiceConfigUpdateRequest request) {
        requireId(id);
        MonitorServiceConfig config = detail(id);
        if (request.serviceName() != null) {
            config.setServiceName(requireText(request.serviceName(), "serviceName不能为空"));
            ensureUnique(config.getServerCode(), config.getServiceName(), id);
        }
        if (request.processName() != null) {
            config.setProcessName(trimToNull(request.processName()));
        }
        if (request.port() != null) {
            config.setPort(request.port());
        }
        if (request.checkInterval() != null) {
            config.setCheckInterval(request.checkInterval());
        }
        updateById(config);
        notifyClientConfigChanged(config, "PUT");
        return config;
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        requireId(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("status只能为0或1");
        }
        MonitorServiceConfig config = detail(id);
        config.setStatus(status);
        updateById(config);
        notifyClientConfigChanged(config, "PUT");
    }

    @Override
    public boolean delete(Long id) {
        requireId(id);
        if (getById(id) == null) {
            throw new IllegalStateException("服务配置不存在");
        }
        MonitorServiceConfig deleted = getById(id);
        boolean removed = removeById(id);
        if (removed) {
            notifyClientConfigChanged(deleted, "DEL");
        }
        return removed;
    }

    @Override
    public List<AgentMonitorConfig> getConfigsByServerCode(String serverCode) {
        if (serverCode == null || serverCode.isBlank()) {
            throw new IllegalArgumentException("serverCode 不能为空");
        }
        String normalized = serverCode.trim();
        LambdaQueryWrapper<MonitorServiceConfig> query = new LambdaQueryWrapper<>();
        query.eq(MonitorServiceConfig::getServerCode, normalized)
                .eq(MonitorServiceConfig::getStatus, DEFAULT_STATUS)
                .orderByAsc(MonitorServiceConfig::getId);
        if (count(new LambdaQueryWrapper<MonitorServiceConfig>()
                .eq(MonitorServiceConfig::getServerCode, normalized)) == 0) {
            throw new IllegalArgumentException("serverCode 不存在: " + normalized);
        }
        return list(query).stream().map(this::toAgentMonitorConfig).collect(Collectors.toList());
    }

    private AgentMonitorConfig toAgentMonitorConfig(MonitorServiceConfig config) {
        AgentMonitorConfig dto = new AgentMonitorConfig();
        dto.setConfigId(config.getId());
        dto.setServiceName(config.getServiceName());
        dto.setProcessName(config.getProcessName());
        dto.setPort(config.getPort());
        dto.setCheckInterval(config.getCheckInterval());
        return dto;
    }

    private void notifyClientConfigChanged(MonitorServiceConfig config, String actionType) {
        Integer status = "DEL".equals(actionType) ? 0 : config.getStatus();
        ClientMonitorConfig item = new ClientMonitorConfig();
        item.setConfigId(config.getId());
        item.setServiceName(config.getServiceName());
        item.setProcessName(config.getProcessName());
        item.setPort(config.getPort());
        item.setCheckInterval(config.getCheckInterval());
        ClientResponse response = ClientResponse.builder()
                .action("MONITOR_CONFIG_CHANGED")
                .serverCode(config.getServerCode())
                .status(status)
                .actionType(actionType)
                .data(java.util.List.of(item))
                .build();
        clientSessionManager.sendToServer(config.getServerCode(), WsUtil.toJson(response));
    }
    private void ensureServerExists(String serverCode) {
        if (serverInfoMapper.selectCount(new LambdaQueryWrapper<com.qingruan.entity.ServerInfo>()
                .eq(com.qingruan.entity.ServerInfo::getServerCode, serverCode)) == 0) {
            throw new IllegalStateException("服务器不存在: " + serverCode);
        }
    }

    private void ensureUnique(String serverCode, String serviceName, Long excludedId) {
        LambdaQueryWrapper<MonitorServiceConfig> query = new LambdaQueryWrapper<MonitorServiceConfig>()
                .eq(MonitorServiceConfig::getServerCode, serverCode)
                .eq(MonitorServiceConfig::getServiceName, serviceName);
        if (excludedId != null) {
            query.ne(MonitorServiceConfig::getId, excludedId);
        }
        if (count(query) > 0) {
            throw new ServiceConfigConflictException("该服务器已存在同名服务配置");
        }
    }

    private static String requireText(String value, String message) {
        String trimmed = value == null ? null : value.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("id必须为正整数");
        }
    }
}
