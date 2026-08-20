package com.idolradar.web;

import java.util.Map;

import com.idolradar.api.ApiResponse;
import com.idolradar.api.IdolRadarStore;
import com.idolradar.auth.AuthInterceptor;
import com.idolradar.auth.AuthService;
import com.idolradar.config.BackendProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 暴露稳定小程序 API 的 HTTP 适配器；所有持久化决策委托给数据层。 */
@RestController
public class ApiController {
    private final AuthService authService;
    private final IdolRadarStore store;
    private final BackendProperties properties;

    public ApiController(AuthService authService, IdolRadarStore store, BackendProperties properties) {
        this.authService = authService;
        this.store = store;
        this.properties = properties;
    }

    @PostMapping("/v1/auth/wechat/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.code()));
    }

    @GetMapping("/v1/me/bootstrap")
    public ApiResponse<Map<String, Object>> bootstrap(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity) {
        return ApiResponse.ok(store.bootstrap(identity.openId()));
    }

    @GetMapping("/v1/home")
    public ApiResponse<Map<String, Object>> home(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity) {
        return ApiResponse.ok(store.getHome(identity.openId()));
    }

    @GetMapping("/v1/feed")
    public ApiResponse<Map<String, Object>> feed(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity,
            @RequestParam(required = false) @Size(max = 512) String cursor) {
        return ApiResponse.ok(store.getFeed(identity.openId(), cursor));
    }

    @GetMapping("/v1/idols")
    public ApiResponse<Map<String, Object>> idols(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity) {
        return ApiResponse.ok(store.listIdols(identity.openId()));
    }

    @PutMapping("/v1/me/idol")
    public ApiResponse<Map<String, Object>> setIdol(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity,
            @Valid @RequestBody SetIdolRequest request) {
        return ApiResponse.ok(store.setIdol(identity.openId(), request.idolId()));
    }

    @PostMapping("/v1/me/subscriptions")
    public ApiResponse<Map<String, Object>> recordSubscription(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity,
            @Valid @RequestBody SubscriptionRequest request) {
        return ApiResponse.ok(store.recordSubscription(
                identity.openId(), request.accepted(), properties.subscribeTemplateId()));
    }

    @PostMapping("/v1/idol-requests")
    public ApiResponse<Map<String, Object>> submitIdolRequest(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity,
            @Valid @RequestBody IdolRequest request) {
        return ApiResponse.ok(store.submitIdolRequest(identity.openId(), request.name(), request.note()));
    }

    @GetMapping("/v1/me/idol-requests")
    public ApiResponse<Map<String, Object>> myIdolRequests(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity) {
        return ApiResponse.ok(store.listMyIdolRequests(identity.openId()));
    }

    @GetMapping("/v1/me/sources")
    public ApiResponse<Map<String, Object>> mySources(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity) {
        return ApiResponse.ok(store.listMySources(identity.openId()));
    }

    @PutMapping("/v1/me/sources/{sourceId}/mute")
    public ApiResponse<Map<String, Object>> muteSource(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity,
            @PathVariable @NotBlank @Size(max = 128) String sourceId) {
        return ApiResponse.ok(store.setSourceMuted(identity.openId(), sourceId, true));
    }

    @DeleteMapping("/v1/me/sources/{sourceId}/mute")
    public ApiResponse<Map<String, Object>> unmuteSource(
            @RequestAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE) AuthService.Identity identity,
            @PathVariable @NotBlank @Size(max = 128) String sourceId) {
        return ApiResponse.ok(store.setSourceMuted(identity.openId(), sourceId, false));
    }

    public record LoginRequest(@NotBlank @Size(min = 4, max = 512) String code) {
    }

    public record SetIdolRequest(@NotBlank @Size(max = 128) String idolId) {
    }

    public record SubscriptionRequest(@NotNull @AssertTrue Boolean accepted) {
    }

    public record IdolRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 200) String note) {
    }
}
