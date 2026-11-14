package com.qingruan.agent.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * AI处置建议单条结构（recommendations 数组元素）。
 */
@Data
public class RecommendationItem implements Serializable {

    /** 优先级（数字越小越优先） */
    private Integer priority;

    /** 建议标题 */
    private String title;

    /** 操作步骤列表 */
    private java.util.List<String> steps;

    /** 预期效果 */
    private String expectedEffect;

    /** 建议执行命令（可选） */
    private String command;
}
