package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.bitpongo.auth.UserDtos.AccountDeletionRequest;
import static com.multind.bitpongo.auth.UserDtos.LoginData;
import static com.multind.bitpongo.auth.UserDtos.UserCreateRequest;
import static com.multind.bitpongo.auth.UserDtos.UserLoginRequest;
import static com.multind.bitpongo.auth.UserDtos.UserResponse;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserApplicationService users;
    private final AccountDeletionService accountDeletion;

    public UserController(UserApplicationService users, AccountDeletionService accountDeletion) {
        this.users = users;
        this.accountDeletion = accountDeletion;
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.success(users.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<LoginData> register(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.success(users.register(request));
    }

    @GetMapping("/profile")
    public UserResponse profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return users.profile(user.id());
    }

    @DeleteMapping("/account")
    public ApiResponse<Void> deleteAccount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AccountDeletionRequest request) {
        accountDeletion.delete(user.id(), request.password());
        return ApiResponse.success(null);
    }
}
