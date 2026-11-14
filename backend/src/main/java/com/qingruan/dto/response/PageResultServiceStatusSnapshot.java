package com.qingruan.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 服务状态快照分页结果
 */
@Data
@Schema(description = "服务状态快照分页结果")
public class PageResultServiceStatusSnapshot implements Serializable {

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "当前页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;

    @Schema(description = "数据列表")
    private List<ServiceStatusSnapshotInfo> list;
}
