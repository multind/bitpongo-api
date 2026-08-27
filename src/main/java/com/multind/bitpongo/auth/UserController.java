package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.bitpongo.auth.UserDtos.AccountDeletionRequest;
import static com.multind.bitpongo.auth.UserDtos.LoginData;
import static com.multind.bitpongo.auth.UserDtos.UserCreateRequest;
import static com.multind.bitpongo.auth.UserDtos.UserLoginRequest;
import static com.multind.bitpongo.auth.UserDtos.UserResponse;
import static com.multind.bitpongo.auth.UserDtos.DeviceTimeZoneRequest;
import static com.multind.bitpongo.auth.UserDtos.TimeZonePreference;
import static com.multind.bitpongo.auth.UserDtos.TimeZonePreferenceRequest;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserApplicationService users;
    private final AccountDeletionService accountDeletion;
    private final UserTimeZoneService timeZones;

    public UserController(
            UserApplicationService users,
            AccountDeletionService accountDeletion,
            UserTimeZoneService timeZones) {
        this.users = users;
        this.accountDeletion = accountDeletion;
        this.timeZones = timeZones;
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

    @GetMapping("/timezone")
    public ApiResponse<TimeZonePreference> timezone(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(timeZones.preference(user.id()));
    }

    @PutMapping("/timezone")
    public ApiResponse<TimeZonePreference> updateTimezone(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TimeZonePreferenceRequest request) {
        return ApiResponse.success(timeZones.save(user.id(), request.mode(), request.timezone()));
    }

    @PostMapping("/timezone/device")
    public ApiResponse<Void> syncDeviceTimezone(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DeviceTimeZoneRequest request) {
        timeZones.syncDeviceZone(user.id(), request.timezone());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/account")
    public ApiResponse<Void> deleteAccount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AccountDeletionRequest request) {
        accountDeletion.delete(user.id(), request.password());
        return ApiResponse.success(null);
    }
}
