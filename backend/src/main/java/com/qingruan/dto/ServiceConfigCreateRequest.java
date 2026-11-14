package com.qingruan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ServiceConfigCreateRequest(
        @NotBlank(message = "serverCode不能为空")
        @Size(max = 64, message = "serverCode长度不能超过64")
        String serverCode,

        @NotBlank(message = "serviceName不能为空")
        @Size(max = 100, message = "serviceName长度不能超过100")
        String serviceName,

        @Size(max = 100, message = "processName长度不能超过100")
        String processName,

        @Min(value = 1, message = "port必须在1到65535之间")
        @Max(value = 65535, message = "port必须在1到65535之间")
        Integer port,

        @NotNull(message = "checkInterval不能为空")
        @Min(value = 1, message = "checkInterval必须大于0")
        Integer checkInterval
) {
    public int effectiveCheckInterval() {
        return checkInterval == null ? 60 : checkInterval;
    }

    public int effectiveStatus() {
        return 1;
    }
}
