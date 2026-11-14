package com.qingruan.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qingruan.agent.entity.AiAnalysisResult;
import com.qingruan.agent.mapper.AiAnalysisResultMapper;
import com.qingruan.agent.vector.VectorStore;
import com.qingruan.entity.AlertEvent;
import com.qingruan.mapper.AlertEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量索引维护：将历史告警批量灌入向量库（一次性回填）。
 * <p>
 * 复用 AiAnalysisService.indexAlert 完成单条索引（embedding + upsert），
 * 所有异常内部降级，不抛出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService {

    private final AlertEventMapper alertEventMapper;
    private final AiAnalysisService aiAnalysisService;

    /**
     * 回填全部告警到向量库（历史数据初始化，跑一次即可）。
     * 已存在向量（相同 id）会被覆盖，天然幂等。
     */
    public int backfillAll() {
        if (!aiAnalysisService.isVectorAvailable()) {
            log.warn("[AI-VECTOR] 向量库不可达，跳过回填");
            return 0;
        }
        List<AlertEvent> alerts = alertEventMapper.selectList(new LambdaQueryWrapper<>());
        int ok = 0;
        for (AlertEvent alert : alerts) {
            try {
                boolean okOne = aiAnalysisService.indexAlert(alert.getId());
                if (okOne) {
                    ok++;
                } else {
                    log.warn("[AI-VECTOR] 回填索引未生效 alertId={}", alert.getId());
                }
            } catch (Exception e) {
                log.error("[AI-VECTOR] 回填失败 alertId={}: {}", alert.getId(), e.getMessage());
            }
        }
        log.info("[AI-VECTOR] 历史回填完成，成功 {} / 总数 {}", ok, alerts.size());
        return ok;
    }
}
