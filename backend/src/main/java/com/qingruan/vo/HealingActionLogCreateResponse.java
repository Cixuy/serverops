package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "新增自愈操作日志响应")
public class HealingActionLogCreateResponse {

    @Schema(description = "服务端落库后的自愈日志主键ID", example = "9999")
    private Long id;
}
