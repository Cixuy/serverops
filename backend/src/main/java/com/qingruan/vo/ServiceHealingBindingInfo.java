package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "服务自愈规则绑定信息")
public class ServiceHealingBindingInfo {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "服务器编码")
    private String serverCode;

    @Schema(description = "监控配置ID")
    private Long configId;

    @Schema(description = "服务名称（冗余，便于展示）")
    private String serviceName;

    @Schema(description = "自愈规则ID")
    private Long ruleId;

    @Schema(description = "自愈规则名称（冗余，便于展示）")
    private String ruleName;

    @Schema(description = "优先级")
    private Integer priority;

    @Schema(description = "状态：0-停用 1-启用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
