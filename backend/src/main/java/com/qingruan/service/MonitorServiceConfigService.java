package com.qingruan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qingruan.common.result.PageResult;
import com.qingruan.dto.ServiceConfigCreateRequest;
import com.qingruan.dto.ServiceConfigUpdateRequest;
import com.qingruan.dto.response.AgentMonitorConfig;
import com.qingruan.entity.MonitorServiceConfig;
import java.util.List;

public interface MonitorServiceConfigService extends IService<MonitorServiceConfig> {

    PageResult<MonitorServiceConfig> page(Integer pageNum, Integer pageSize,
                                           String serverCode, String keyword, Integer status);

    MonitorServiceConfig detail(Long id);

    MonitorServiceConfig create(ServiceConfigCreateRequest request);

    MonitorServiceConfig update(Long id, ServiceConfigUpdateRequest request);

    void updateStatus(Long id, Integer status);

    boolean delete(Long id);

    List<AgentMonitorConfig> getConfigsByServerCode(String serverCode);
}
