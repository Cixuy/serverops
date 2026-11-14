package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "为某服务绑定自愈规则请求")
public class ServiceHealingBindingCreateRequest {

    @Schema(description = "服务器编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serverCode;

    @Schema(description = "监控配置ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long configId;

    @Schema(description = "自愈规则ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long ruleId;

    @Schema(description = "优先级，数值越大优先级越高，默认0", example = "0")
    private Integer priority;
}
