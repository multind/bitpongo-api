package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@RequestMapping("/api/users/security")
public class AccountSecurityController {
    private final AccountSecurityService security;
    private final AccountRecoveryService recovery;
    public AccountSecurityController(AccountSecurityService security,AccountRecoveryService recovery) { this.security=security; this.recovery=recovery; }
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Status(boolean emailEnabled) {}
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Challenge(String challengeId) {}
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record CodeRequest(@NotBlank @Size(max=16) String purpose, @NotBlank @jakarta.validation.constraints.Email @Size(max=100) String email, @Size(max=128) String oldPassword) {}
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Reset(@NotBlank @Size(max=36) String challengeId,@NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9]{8}") String code,@NotBlank @Size(min=12,max=128) String newPassword) {}
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record EmailChange(@NotBlank @Size(max=36) String challengeId,@NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9]{8}") String code,@NotBlank @Size(max=128) String oldPassword) {}
    @GetMapping("/status") public ApiResponse<Status> status() { return ApiResponse.success(new Status(recovery.available())); }
    @PostMapping("/code") public ApiResponse<Challenge> code(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody CodeRequest request,jakarta.servlet.http.HttpServletRequest http) {
        return ApiResponse.success(new Challenge(recovery.request(request.purpose(),request.email(),request.oldPassword(),user==null?null:user.id(),http.getRemoteAddr())));
    }
    @PostMapping("/reset") public ApiResponse<Void> reset(@Valid @RequestBody Reset request) {
        recovery.reset(request.challengeId(),request.code(),request.newPassword()); return ApiResponse.success(null);
    }
    @PostMapping("/email") public ApiResponse<Void> email(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody EmailChange request) {
        recovery.email(user.id(),request.challengeId(),request.code(),request.oldPassword()); return ApiResponse.success(null);
    }
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record PasswordChange(@NotBlank @Size(max=128) String oldPassword, @NotBlank @Size(min=12,max=128) String newPassword) {}
    @PostMapping("/password")
    public ApiResponse<Void> password(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody PasswordChange request) {
        recovery.passwordAttempt(user.id());
        security.changePassword(user.id(),request.oldPassword(),request.newPassword());
        return ApiResponse.success(null);
    }
}
