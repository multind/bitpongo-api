package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/alerts")
public class PriceAlertController {
    private final PriceAlertService service;
    public PriceAlertController(PriceAlertService service) { this.service = service; }
    @PostMapping public ApiResponse<Long> create(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody AlertDtos.Create body) { return ApiResponse.success(service.create(user.id(), body)); }
    @GetMapping public ApiResponse<AlertDtos.Page<AlertDtos.Rule>> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="all") String status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.list(user.id(),q,status,page,size)); }
    @GetMapping("/{id}") public ApiResponse<AlertDtos.Rule> detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) { return ApiResponse.success(service.detail(user.id(),id)); }
    @PutMapping("/{id}/enabled") public ApiResponse<Void> enabled(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id, @RequestBody AlertDtos.Enabled body) { service.enabled(user.id(),id,body.enabled()); return ApiResponse.success(null); }
    @PostMapping("/{id}/copy") public ApiResponse<Long> copy(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) { return ApiResponse.success(service.copy(user.id(),id)); }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) { service.delete(user.id(),id); return ApiResponse.success(null); }
    @PutMapping("/{id}") public ApiResponse<Void> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id, @RequestBody AlertDtos.Create body) { service.update(user.id(),id,body); return ApiResponse.success(null); }
    @PostMapping("/bulk") public ApiResponse<Void> bulk(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody AlertDtos.Bulk body) { service.bulk(user.id(),body); return ApiResponse.success(null); }
}
