package com.qingruan.agent.service;

import com.qingruan.agent.vo.AiAnalysisResultVO;
import com.qingruan.agent.vo.FeedbackRequest;

/**
 * 告警 AI 分析业务接口。
 */
public interface AiAnalysisService {

    /** 手动触发：分析指定告警，返回新建分析结果（已分析过则幂等返回已有结果） */
    AiAnalysisResultVO analyze(Long alertId);

    /** 当前告警的 AI 分析结果（每个告警仅一条，返回单条或 null） */
    AiAnalysisResultVO getByAlert(Long alertId);

    /** 回写用户反馈 */
    void saveFeedback(Long id, FeedbackRequest req);

    /** 将某条告警索引进向量库（用于历史回填；分析成功后会自动调用）。返回是否成功。 */
    boolean indexAlert(Long alertId);

    /** 向量库是否可用（用于回填前判断与降级） */
    boolean isVectorAvailable();
}
