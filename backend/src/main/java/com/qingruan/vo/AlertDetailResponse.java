package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "告警详情及处置时间线")
public class AlertDetailResponse {

    @Schema(description = "告警事件信息")
    private AlertEventInfo alertInfo;

    @Schema(description = "处置操作流水（按时间升序）")
    private List<AlertActionLogInfo> actionLogs;
}
