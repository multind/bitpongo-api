package com.multind.bitpongo.plan;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.bitpongo.plan.PlanDtos.PlanView;

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
            @PathVariable long planId,
            @RequestParam(name = "include_orders", defaultValue = "true") boolean includeOrders) {
        return ApiResponse.success(plans.detail(user.id(), planId, includeOrders));
    }

    @GetMapping("/{planId}/orders")
    public ApiResponse<PlanDtos.OrderPage> orders(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long planId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(plans.orders(user.id(), planId, page, size));
    }
}
