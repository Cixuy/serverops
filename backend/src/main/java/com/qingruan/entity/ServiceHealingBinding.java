package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_healing_rule_binding")
@Schema(description = "服务-自愈规则绑定关系")
public class ServiceHealingBinding {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "服务器编码")
    private String serverCode;

    @Schema(description = "监控服务配置ID")
    private Long configId;

    @Schema(description = "自愈规则ID")
    private Long ruleId;

    @Schema(description = "优先级（数值越大优先级越高）")
    private Integer priority;

    @Schema(description = "状态：1-启用监听 / 0-停用")
    private Integer status;

    @Schema(description = "绑定时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
