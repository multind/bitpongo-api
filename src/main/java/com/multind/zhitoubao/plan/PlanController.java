package com.multind.zhitoubao.plan;

import com.multind.zhitoubao.auth.AuthenticatedUser;
import com.multind.zhitoubao.common.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.zhitoubao.plan.PlanDtos.PlanView;

@RestController
@RequestMapping("/api/plans")
public class PlanController {
    private final PlanApplicationService plans;

    public PlanController(PlanApplicationService plans) { this.plans = plans; }

    @GetMapping("/list/active")
    public ApiResponse<List<PlanView>> active(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(plans.active(user.id()));
    }

    @GetMapping("/{planId}/{status}")
    public ApiResponse<Void> updateStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long planId,
            @PathVariable String status) {
        plans.updateStatus(user.id(), planId, status);
        return ApiResponse.success(null);
    }

    @GetMapping("/{planId}")
    public ApiResponse<PlanView> detail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long planId) {
        return ApiResponse.success(plans.detail(user.id(), planId));
    }
}
