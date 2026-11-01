package com.multind.bitpongo.preset;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!test")
@RestController
@RequestMapping("/api/presets")
public class PresetController {
    private final PresetService presets;

    public PresetController(PresetService presets) {
        this.presets = presets;
    }

    @GetMapping
    public ApiResponse<PresetDtos.Page> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "comprehensive") String sort,
            @RequestParam(defaultValue = "all") String owner,
            @RequestParam(defaultValue = "all") String exchange,
            @RequestParam(defaultValue = "all") String runtime,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(presets.list(user.id(), q, sort, owner, exchange, runtime, type, page, size));
    }

    @GetMapping("/{key}")
    public ApiResponse<PresetDtos.Preset> detail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String key) {
        return ApiResponse.success(presets.detail(user.id(), key));
    }

    @GetMapping("/visibility/{type}/{strategyId}")
    public ApiResponse<PresetDtos.Visibility> visibility(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String type,
            @PathVariable long strategyId) {
        return ApiResponse.success(presets.visibility(user.id(), type, strategyId));
    }

    @PutMapping("/visibility/{type}/{strategyId}")
    public ApiResponse<PresetDtos.Visibility> updateVisibility(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String type,
            @PathVariable long strategyId,
            @RequestBody PresetDtos.VisibilityRequest request) {
        return ApiResponse.success(presets.updateVisibility(user.id(), type, strategyId, request.visible()));
    }

    @PostMapping("/{key}/favorite")
    public ApiResponse<PresetDtos.Favorite> favorite(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String key) {
        return ApiResponse.success(presets.favorite(user.id(), key));
    }
}