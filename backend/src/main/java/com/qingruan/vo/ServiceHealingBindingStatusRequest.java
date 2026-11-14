package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "启停绑定监听状态请求")
public class ServiceHealingBindingStatusRequest {

    @Schema(description = "状态: 0-停用 1-启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer status;
}
