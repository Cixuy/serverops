package com.qingruan.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "分页结果封装")
public class PageResult<T> implements Serializable {

    @Schema(description = "总记录数", example = "50")
    private long total;

    @Schema(description = "页码", example = "1")
    private long pageNum;

    @Schema(description = "每页数量", example = "10")
    private long pageSize;

    @Schema(description = "数据列表")
    private List<T> list;

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> list) {
        PageResult<T> result = new PageResult<>();
        result.pageNum = pageNum;
        result.pageSize = pageSize;
        result.total = total;
        result.list = list;
        return result;
    }
}
