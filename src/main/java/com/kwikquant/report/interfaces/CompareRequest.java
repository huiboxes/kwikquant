package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompareRequest(
        @Schema(description = "对比报告 ID 列表，2-20 个", example = "[42, 43]")
                @NotNull
                @Size(min = 2, max = 20, message = "reportIds must contain 2-20 entries")
                List<Long> reportIds) {}
