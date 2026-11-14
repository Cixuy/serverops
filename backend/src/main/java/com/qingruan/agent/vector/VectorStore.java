package com.qingruan.agent.vector;

import java.util.List;

/**
 * 向量存储抽象接口（便于将来切换 Milvus/pgvector 等实现）。
 */
public interface VectorStore {

    /**
     * 写入/更新一条向量。id 相同时覆盖（幂等）。
     *
     * @param id         唯一标识（此处语义为 alert_id 的字符串形式）
     * @param vector     向量
     * @param document   原文档（用于注入 prompt）
     * @param metadata   元数据
     * @return 是否写入成功
     */
    boolean upsert(String id, float[] vector, String document, java.util.Map<String, Object> metadata);

    /**
     * 向量检索 Top-K 相似项。
     *
     * @param vector     查询向量
     * @param topK       返回条数
     * @param excludeId  需排除的 id（通常为当前告警自身），可为 null
     */
    List<SimilarCase> search(float[] vector, int topK, String excludeId);

    /** 是否存在有效集合（用于降级判断） */
    boolean available();
}
