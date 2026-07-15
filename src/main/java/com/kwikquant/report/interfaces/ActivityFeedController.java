package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.ActivityFeedService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class ActivityFeedController {

    private final ActivityFeedService activityFeedService;

    public ActivityFeedController(ActivityFeedService activityFeedService) {
        this.activityFeedService = activityFeedService;
    }

    @GetMapping("/api/v1/activity-feed")
    @Operation(summary = "获取活动流", description = "需 JWT 鉴权。返回当前用户的活动事件列表，按时间倒序。")
    public ApiResponse<List<ActivityFeedItemDto>> feed(
            @Parameter(description = "返回条数，默认 10，上限 50")
                    @RequestParam(defaultValue = "10")
                    @Max(50)
                    int limit) {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(activityFeedService.getFeed(userId, limit));
    }
}
