package com.qingruan.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 组合触发条件表达式树（可递归）。
 * <p>
 * 根节点与嵌套组合节点：含 {@code logic}(AND/OR) 与 {@code conditions}（子节点数组）。
 * 叶子节点：含 {@code type}（METRIC / SERVICE_STATUS / LOG_MATCH 等），
 * 并按类型携带相应字段：
 *   - METRIC: metric, operator, value
 *   - SERVICE_STATUS: operator, value
 *   - LOG_MATCH: log_file_path, regex_pattern, match_window_seconds(可选)
 */
@Data
@Schema(description = "组合触发条件表达式树（可递归）")
public class TriggerConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "组合逻辑：AND / OR（组合节点）")
    private String logic;

    @Schema(description = "子节点列表（组合节点）；元素可为叶子条件或嵌套组合")
    private List<TriggerConfig> conditions;

    @Schema(description = "叶子类型：METRIC / SERVICE_STATUS / LOG_MATCH 等（叶子节点）")
    private String type;

    @Schema(description = "指标名称（METRIC）")
    private String metric;

    @Schema(description = "比较运算符：> < >= <= == !=（METRIC / SERVICE_STATUS）")
    private String operator;

    @Schema(description = "比较值（数值或字符串）")
    private Object value;

    @JsonProperty("log_file_path")
    @Schema(description = "日志文件路径（LOG_MATCH）")
    private String logFilePath;

    @JsonProperty("regex_pattern")
    @Schema(description = "匹配正则（LOG_MATCH）")
    private String regexPattern;

    @JsonProperty("match_window_seconds")
    @Schema(description = "匹配时间窗口(秒)（LOG_MATCH，可选）")
    private Integer matchWindowSeconds;
}
