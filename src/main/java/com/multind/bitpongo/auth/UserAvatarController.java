package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.ApiResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("!test")
@RequestMapping("/api/users/profile/avatar")
public class UserAvatarController {
    private final UserAvatarService avatars;
    public UserAvatarController(UserAvatarService avatars) { this.avatars = avatars; }
    @GetMapping public ApiResponse<UserAvatarService.Avatar> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(avatars.get(user.id()));
    }
    @PostMapping(consumes="multipart/form-data")
    public ApiResponse<UserAvatarService.Avatar> upload(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(avatars.save(user.id(), file.getBytes()));
    }
    @DeleteMapping public ApiResponse<UserAvatarService.Avatar> remove(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(avatars.remove(user.id()));
    }
}
