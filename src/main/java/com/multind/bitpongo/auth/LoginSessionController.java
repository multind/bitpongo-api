package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@RequestMapping("/api/users/sessions")
public class LoginSessionController {
    private final LoginSessionService sessions;
    public LoginSessionController(LoginSessionService sessions) { this.sessions=sessions; }
    @GetMapping public ApiResponse<LoginSessionService.Listing> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(sessions.list(user.id(),authorization.substring(7).strip()));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> revoke(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id) {
        sessions.revoke(user.id(),id);
        return ApiResponse.success(null);
    }
}
