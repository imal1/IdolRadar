package com.idolradar.web;

import java.util.Map;
import java.util.UUID;

import com.idolradar.admin.AdminAuthInterceptor;
import com.idolradar.admin.AdminAuthService;
import com.idolradar.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 管理员登录与访问控制 HTTP 入口；不复用小程序登录接口或身份。 */
@RestController
public class AdminController {
    private final AdminAuthService authService;

    public AdminController(AdminAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/admin/v1/auth/login")
    public ApiResponse<AdminAuthService.LoginResult> login(
            @Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    @GetMapping("/admin/v1/me")
    public ApiResponse<AdminAuthService.CurrentAdmin> currentAdmin(
            @RequestAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE) AdminAuthService.Identity identity) {
        return ApiResponse.ok(identity.currentAdmin());
    }

    @PostMapping("/admin/v1/auth/logout")
    public ApiResponse<Map<String, Boolean>> logout(
            @RequestAttribute(AdminAuthInterceptor.IDENTITY_ATTRIBUTE) AdminAuthService.Identity identity) {
        authService.logout(identity);
        return ApiResponse.ok(Map.of("revoked", true));
    }

    @PostMapping("/admin/v1/admins/{adminId}/revoke")
    public ApiResponse<Map<String, Boolean>> revokeAccess(@PathVariable UUID adminId) {
        authService.revokeAccess(adminId);
        return ApiResponse.ok(Map.of("revoked", true));
    }

    public record LoginRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9._-]{3,64}$")
            String username,
            // 只校验上限：口令长度下限属于创建口令时的策略（见 AdminAuthService.bootstrap），
            // 在登录接口重复断言会把不满足现行策略的既有账号挡在验密之前，
            // 且返回 400 而非 401，等于告诉调用方“这个口令没进到比对环节”。
            @NotBlank
            @Size(max = 256)
            String password) {
    }
}
