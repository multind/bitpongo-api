package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@RequestMapping("/api/users/profile/details")
public class UserProfileController {
    private final UserProfileService profiles;
    public UserProfileController(UserProfileService profiles) { this.profiles=profiles; }
    @GetMapping public ApiResponse<UserProfileService.Profile> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(profiles.get(user.id()));
    }
    @PutMapping public ApiResponse<UserProfileService.Profile> save(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody UserProfileService.Profile request) {
        return ApiResponse.success(profiles.save(user.id(),request));
    }
}
