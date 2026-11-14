package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "告警处理操作请求")
public class AlertActionRequest {

    @Schema(description = "操作类型: AUTO_HEAL(自愈) / MANUAL_RECOVER(手动恢复) / IGNORE(搁置)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String actionType;

    @Schema(description = "操作详情/备注说明")
    private String actionDetail;
}
