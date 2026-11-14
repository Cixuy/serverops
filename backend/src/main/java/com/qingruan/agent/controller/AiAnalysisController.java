package com.qingruan.agent.controller;

import com.qingruan.agent.service.AiAnalysisService;
import com.qingruan.agent.service.VectorIndexService;
import com.qingruan.agent.vo.AiAnalysisResultVO;
import com.qingruan.agent.vo.FeedbackRequest;
import com.qingruan.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警 AI 分析接口。
 */
@RestController
@RequestMapping("/ai-analysis")
@RequiredArgsConstructor
@Tag(name = "12-AI告警分析", description = "告警智能分析与用户反馈")
public class AiAnalysisController {

    private final AiAnalysisService service;
    private final VectorIndexService vectorIndexService;

    @Operation(summary = "手动触发告警分析", description = "对指定告警调用本地 LLM 生成根因/建议并落库")
    @PostMapping("/alerts/{alertId}/analyze")
    public Result<AiAnalysisResultVO> analyze(
            @Parameter(description = "告警事件ID", example = "1")
            @PathVariable("alertId") Long alertId) {
        return Result.ok(service.analyze(alertId));
    }

    @Operation(summary = "查询告警的分析结果", description = "返回当前告警的 AI 分析结果（每个告警仅一条，无则返回 null）")
    @GetMapping("/alerts/{alertId}")
    public Result<AiAnalysisResultVO> getByAlert(
            @Parameter(description = "告警事件ID", example = "1")
            @PathVariable("alertId") Long alertId) {
        return Result.ok(service.getByAlert(alertId));
    }

    @Operation(summary = "回写用户反馈", description = "对 AI 分析结果的准确性给出反馈")
    @PostMapping("/{id}/feedback")
    public Result<Void> feedback(
            @Parameter(description = "分析记录ID", example = "1")
            @PathVariable("id") Long id,
            @Valid @RequestBody FeedbackRequest req) {
        service.saveFeedback(id, req);
        return Result.ok();
    }

    @Operation(summary = "历史告警向量回填", description = "将全部历史告警灌入向量库（一次性，Chroma 不可用时返回 0）")
    @PostMapping("/index/backfill")
    public Result<Integer> backfill() {
        return Result.ok(vectorIndexService.backfillAll());
    }
}
