package com.qingruan.agent.vector;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qingruan.agent.config.VectorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Chroma HTTP API 的向量存储实现（零额外 Maven 依赖，仅用 RestTemplate）。
 * <p>
 * 集合使用余弦距离（hnsw:space=cosine）。所有异常均降级，保证分析主流程不中断。
 * <p>
 * 注意：Chroma v2 的 upsert/query 路径中需要的是「集合 UUID」而非集合名，
 * 因此本类在首次使用时通过 get-or-create 解析集合名到 UUID 并缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromaVectorStore implements VectorStore {

    private final RestTemplate chromaRestTemplate;
    private final VectorProperties props;

    /** 集合名（来自配置） */
    private String collectionName() {
        return props.getChroma().getCollection();
    }

    /** 集合 UUID（解析后缓存，避免每次请求都解析） */
    private final AtomicReference<String> collectionUuidRef = new AtomicReference<>();

    private String base() {
        return props.getChroma().getBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public boolean available() {
        try {
            String url = base() + "/api/v2/heartbeat";
            String resp = chromaRestTemplate.getForObject(url, String.class);
            return resp != null;
        } catch (Exception e) {
            log.warn("[AI-VECTOR] Chroma 不可达: {}", e.getMessage());
            return false;
        }
    }

    /** 确保 default database 存在（Chroma v2 某些版本不会自动创建 database） */
    private void ensureDatabase() {
        try {
            String url = base() + "/api/v2/tenants/default/databases";
            JSONObject body = new JSONObject();
            body.put("name", "default");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            chromaRestTemplate.postForObject(
                    url, new HttpEntity<>(body.toJSONString(), headers), String.class);
        } catch (Exception ignored) {
            // 已存在时返回错误，忽略即可
        }
    }

    /** get-or-create 集合，返回其 UUID（用于 upsert/query 路径） */
    private String resolveCollectionId() {
        ensureDatabase();
        String cached = collectionUuidRef.get();
        if (cached != null) {
            return cached;
        }
        String name = collectionName();
        // 1) 先尝试按名查找（list 里匹配）
        try {
            String listUrl = base() + "/api/v2/tenants/default/databases/default/collections";
            String listResp = chromaRestTemplate.getForObject(listUrl, String.class);
            if (listResp != null) {
                JSONArray arr = JSON.parseArray(listResp);
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        if (name.equals(c.getString("name"))) {
                            String uuid = c.getString("id");
                            collectionUuidRef.set(uuid);
                            return uuid;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[AI-VECTOR] 列举集合失败，将尝试创建: {}", e.getMessage());
        }
        // 2) 不存在则创建
        try {
            String createUrl = base() + "/api/v2/tenants/default/databases/default/collections";
            JSONObject body = new JSONObject();
            body.put("name", name);
            body.put("metadata", JSON.parseObject("{\"hnsw:space\":\"cosine\"}"));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = chromaRestTemplate.postForObject(
                    createUrl, new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (resp != null) {
                JSONObject created = JSON.parseObject(resp);
                String uuid = created != null ? created.getString("id") : null;
                if (uuid != null) {
                    collectionUuidRef.set(uuid);
                    return uuid;
                }
            }
        } catch (Exception e) {
            // 并发创建可能返回已存在错误，忽略；下次解析时走 list 分支
            log.debug("[AI-VECTOR] 创建集合忽略: {}", e.getMessage());
        }
        // 3) 回退：可能因并发已创建，再 list 一次
        try {
            String listUrl = base() + "/api/v2/tenants/default/databases/default/collections";
            String listResp = chromaRestTemplate.getForObject(listUrl, String.class);
            if (listResp != null) {
                JSONArray arr = JSON.parseArray(listResp);
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        if (name.equals(c.getString("name"))) {
                            String uuid = c.getString("id");
                            collectionUuidRef.set(uuid);
                            return uuid;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public boolean upsert(String id, float[] vector, String document, Map<String, Object> metadata) {
        if (id == null || vector == null) {
            return false;
        }
        String collId = resolveCollectionId();
        if (collId == null) {
            log.warn("[AI-VECTOR] 无法解析集合，跳过 upsert id={}", id);
            return false;
        }
        try {
            String url = base() + "/api/v2/tenants/default/databases/default/collections/"
                    + collId + "/upsert";

            JSONArray ids = new JSONArray();
            ids.add(id);
            JSONArray embeddings = new JSONArray();
            embeddings.add(toJsonArray(vector));
            JSONArray documents = new JSONArray();
            documents.add(document == null ? "" : document);
            JSONArray metadatas = new JSONArray();
            metadatas.add(metadata == null ? new JSONObject() : new JSONObject(metadata));

            JSONObject body = new JSONObject();
            body.put("ids", ids);
            body.put("embeddings", embeddings);
            body.put("documents", documents);
            body.put("metadatas", metadatas);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            chromaRestTemplate.postForObject(
                    url, new HttpEntity<>(body.toJSONString(), headers), String.class);
            return true;
        } catch (Exception e) {
            log.error("[AI-VECTOR] upsert 失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public List<SimilarCase> search(float[] vector, int topK, String excludeId) {
        if (vector == null) {
            return new ArrayList<>();
        }
        String collId = resolveCollectionId();
        if (collId == null) {
            return new ArrayList<>();
        }
        // 多取 1 条以便排除自身
        int k = excludeId != null ? topK + 1 : topK;
        try {
            String url = base() + "/api/v2/tenants/default/databases/default/collections/"
                    + collId + "/query";

            JSONObject body = new JSONObject();
            JSONArray queryVecs = new JSONArray();
            queryVecs.add(toJsonArray(vector));
            body.put("query_embeddings", queryVecs);
            body.put("n_results", k);
            body.put("include", JSON.parseArray("[\"documents\",\"metadatas\",\"distances\"]"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = chromaRestTemplate.postForObject(
                    url, new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (resp == null || resp.isBlank()) {
                return new ArrayList<>();
            }
            return parseQuery(resp, excludeId);
        } catch (Exception e) {
            log.error("[AI-VECTOR] query 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<SimilarCase> parseQuery(String respJson, String excludeId) {
        List<SimilarCase> result = new ArrayList<>();
        try {
            JSONObject root = JSON.parseObject(respJson);
            JSONArray ids = root.getJSONArray("ids");
            JSONArray documents = root.getJSONArray("documents");
            JSONArray metadatas = root.getJSONArray("metadatas");
            JSONArray distances = root.getJSONArray("distances");
            if (ids == null || ids.isEmpty()) {
                return result;
            }
            // Chroma 返回的为数组的数组（每个 query 对应一组）
            JSONArray firstIds = ids.getJSONArray(0);
            JSONArray firstDocs = documents != null ? documents.getJSONArray(0) : null;
            JSONArray firstMeta = metadatas != null ? metadatas.getJSONArray(0) : null;
            JSONArray firstDist = distances != null ? distances.getJSONArray(0) : null;

            for (int i = 0; i < firstIds.size(); i++) {
                String id = firstIds.getString(i);
                if (excludeId != null && excludeId.equals(id)) {
                    continue;
                }
                SimilarCase c = new SimilarCase();
                c.setId(id);
                c.setDocument(firstDocs != null ? firstDocs.getString(i) : null);
                c.setMetadata(firstMeta != null && firstMeta.get(i) != null
                        ? firstMeta.getJSONObject(i).toJavaObject(Map.class) : null);
                c.setDistance(firstDist != null && firstDist.get(i) != null
                        ? firstDist.getFloat(i) : null);
                result.add(c);
            }
        } catch (Exception e) {
            log.error("[AI-VECTOR] 解析 query 响应失败: {}", e.getMessage());
        }
        if (!result.isEmpty()) {
            log.info("[AI-VECTOR] 检索命中相似历史 {} 条（已排除自身）", result.size());
        }
        return result;
    }

    private JSONArray toJsonArray(float[] vector) {
        JSONArray arr = new JSONArray();
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }
}
