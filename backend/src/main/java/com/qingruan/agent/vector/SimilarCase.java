package com.qingruan.agent.vector;

import lombok.Data;

import java.util.Map;

/**
 * 向量检索命中的一条相似历史告警。
 */
@Data
public class SimilarCase {
    /** 该案例在向量库中的唯一 id（此处即 alert_id） */
    private String id;
    /** 召回的文档文本（用于注入 prompt） */
    private String document;
    /** 元数据（alert_id / 来源类型等） */
    private Map<String, Object> metadata;
    /** 相似度距离（cosine 下越小越相似；未提供时为 null） */
    private Float distance;
}
