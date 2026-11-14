package com.qingruan.controller;

import com.qingruan.common.result.Result;
import com.qingruan.dto.request.MetricsReportRequest;
import com.qingruan.dto.response.MetricsQueryResponse;
import com.qingruan.service.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 监控指标接口: 上报 + 查询
 */
@RestController
@RequestMapping("/metrics")
@RequiredArgsConstructor
@Validated
public class MetricsController {

    private final AgentService agentService;

    /**
     * POST /api/v1/metrics
     * Agent 定时(如5秒)上报CPU、内存、网络等时序数据
     */
    @PostMapping
    public Result<Object> report(@Valid @RequestBody MetricsReportRequest request) {
        agentService.saveMetrics(request);
        return Result.ok(Collections.emptyMap());
    }

    /**
     * GET /api/v1/metrics/query
     * 从 InfluxDB 查询指定服务器的资源使用趋势数据
     */
    @GetMapping("/query")
    public Result<MetricsQueryResponse> query(
            @RequestParam("serverCode") String serverCode,
            @RequestParam("range") @Pattern(regexp = "30m|1h|6h|1d",
                    message = "range 仅支持 30m/1h/6h/1d") String range) {
        return Result.ok(agentService.queryMetrics(serverCode, range));
    }
}
