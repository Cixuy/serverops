package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "启停自愈规则请求")
public class HealingRuleStatusRequest {

    @Schema(description = "状态: 0-停用 1-启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer status;
}
