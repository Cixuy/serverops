package com.qingruan.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qingruan.agent.config.EmbeddingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用本地 llama.cpp server 的 /v1/embeddings 生成文本向量。
 * <p>
 * 与 chat(Qwen3.5) 实例分离，通常指向加载了 bge-m3 的另一个实例。
 * 任何异常均降级为 null，保证分析主流程不因此中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final RestTemplate embeddingRestTemplate;
    private final EmbeddingProperties props;

    /**
     * 生成单条文本的向量。失败返回 null。
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String url = props.getBaseUrl().replaceAll("/+$", "") + "/embeddings";

        JSONObject body = new JSONObject();
        body.put("model", props.getModel());
        body.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String resp = embeddingRestTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), String.class);
            if (resp == null || resp.isBlank()) {
                log.error("[AI-EMBED] embeddings 返回空");
                return null;
            }
            return parseEmbedding(resp);
        } catch (Exception e) {
            log.error("[AI-EMBED] 调用 embeddings 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private float[] parseEmbedding(String respJson) {
        try {
            JSONObject root = JSON.parseObject(respJson);
            JSONArray data = root.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.error("[AI-EMBED] 响应缺少 data");
                return null;
            }
            // OpenAI 兼容：data[0].embedding 为向量数组
            JSONArray emb = data.getJSONObject(0).getJSONArray("embedding");
            if (emb == null || emb.isEmpty()) {
                return null;
            }
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = emb.getBigDecimal(i).floatValue();
            }
            return vec;
        } catch (Exception e) {
            log.error("[AI-EMBED] 解析 embeddings 响应失败: {}", e.getMessage());
            return null;
        }
    }
}
