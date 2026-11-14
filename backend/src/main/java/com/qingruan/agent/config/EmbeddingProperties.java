package com.qingruan.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding 配置，绑定 ai.analysis.embedding.*。
 * <p>
 * 走本地 llama.cpp server 的 /v1/embeddings（与 chat 实例分开部署，便于加载 bge-m3 等专用模型）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.analysis.embedding")
public class EmbeddingProperties {
    /** embeddings 端点前缀，如 http://127.0.0.1:8082/v1 */
    private String baseUrl = "http://127.0.0.1:8082/v1";
    /** 实际加载的 embedding 模型名（对应 llama.cpp 的 -a 别名或文件名） */
    private String model = "bge-m3";
    /** 请求超时(ms) */
    private int timeoutMs = 30000;
}
