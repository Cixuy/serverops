package com.qingruan.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量检索配置，绑定 ai.analysis.vector.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.analysis.vector")
public class VectorProperties {
    private Chroma chroma = new Chroma();

    @Data
    public static class Chroma {
        /** Chroma HTTP 服务地址，如 http://127.0.0.1:8000 */
        private String baseUrl = "http://127.0.0.1:8000";
        /** 集合名（首次写入时自动创建） */
        private String collection = "serverops_alert_analysis";
        /** 单次检索返回的相似条数 */
        private int topK = 5;
        /** 请求超时(ms) */
        private int timeoutMs = 30000;
    }
}
