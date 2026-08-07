package com.multind.zhitoubao.auth;

import com.multind.zhitoubao.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.zhitoubao.auth.UserDtos.LoginData;
import static com.multind.zhitoubao.auth.UserDtos.UserCreateRequest;
import static com.multind.zhitoubao.auth.UserDtos.UserLoginRequest;
import static com.multind.zhitoubao.auth.UserDtos.UserResponse;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserApplicationService users;

    public UserController(UserApplicationService users) {
        this.users = users;
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.success(users.login(request));
    }

    @PostMapping("/register")
    public UserResponse register(@Valid @RequestBody UserCreateRequest request) {
        return users.register(request);
    }

    @GetMapping("/profile")
    public UserResponse profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return users.profile(user.id());
    }

    @PostMapping("/v1/login")
    public ApiResponse<LoginData> wordpressLogin(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.success(users.wordpressLogin(request));
    }
}
