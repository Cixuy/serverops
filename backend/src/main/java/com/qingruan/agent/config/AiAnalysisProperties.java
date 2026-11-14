package com.qingruan.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 分析相关配置，绑定 application.yml 的 {@code ai.analysis.ollama.*} 前缀。
 * <p>
 * 注：字段命名沿用 ollama 前缀仅为与历史约定一致；实现上对接的是
 * 任意 OpenAI 兼容端点（此处为本地 llama.cpp server 托管的 Qwen3.5）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.analysis.ollama")
public class AiAnalysisProperties {

    /** 端点基础地址（含 /v1），如 http://127.0.0.1:8081/v1 */
    private String baseUrl = "http://127.0.0.1:8081/v1";

    /** 鉴权 Token（本地服务可留空） */
    private String apiKey = "";

    /** 模型别名，需与 llama-server -a 别名一致 */
    private String model = "deepseek-v4-flash";

    /** 读取超时（毫秒）：Qwen3.5 思维链 + 9B-Q4 推理较慢，默认 120s */
    private int timeoutMs = 120_000;

    /** 采样温度，越低越确定 */
    private double temperature = 0.2;

    /**
     * 是否启用 JSON 模式（response_format=json_object）。
     * <p>
     * - DeepSeek {@code deepseek-chat}（V3）支持 JSON 模式，保持 true 可约束输出结构；
     * - DeepSeek {@code deepseek-reasoner}（R1）官方不建议配合 response_format 使用，
     *   应设为 false，否则可能报错。默认 true 以兼容多数 chat 模型。
     */
    private boolean jsonMode = true;
}
