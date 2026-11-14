package com.qingruan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ServiceConfigUpdateRequest(
        @Size(max = 100, message = "serviceName长度不能超过100")
        String serviceName,

        @Size(max = 100, message = "processName长度不能超过100")
        String processName,

        @Min(value = 1, message = "port必须在1到65535之间")
        @Max(value = 65535, message = "port必须在1到65535之间")
        Integer port,

        @Min(value = 1, message = "checkInterval必须大于0")
        Integer checkInterval
) {
}
