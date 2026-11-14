package com.qingruan.agent.provider;

import com.qingruan.agent.dto.AnalysisInput;
import com.qingruan.agent.dto.AnalysisOutput;
import com.qingruan.agent.vector.SimilarCase;

import java.util.List;

/**
 * 告警分析抽象接口（策略模式）。
 * <p>
 * 具体实现可以是本地 llama.cpp server、远端 OpenAI 兼容端点、占位 Mock 等。
 * 业务层仅依赖本接口，切换模型/供应商时无需改动 service。
 */
public interface AnalysisProvider {

    /**
     * 分析一条告警。
     *
     * @param input        告警上下文
     * @param similarCases 向量检索到的相似历史告警（RAG 参考上下文，可为空）
     * @return 结构化分析结果（recommendations 保证非空）
     */
    AnalysisOutput analyze(AnalysisInput input, List<SimilarCase> similarCases);
}
