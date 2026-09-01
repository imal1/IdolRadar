package com.idolradar.admin;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/** 当前管理请求的安全业务摘要；只允许控制器写入白名单字段。 */
public final class AdminAuditContext {
    private static final String ATTRIBUTE = AdminAuditContext.class.getName() + ".details";

    private AdminAuditContext() {
    }

    public static void attach(HttpServletRequest request, String beforeSummary, String afterSummary) {
        attach(request, null, beforeSummary, afterSummary);
    }

    public static void attach(
            HttpServletRequest request,
            String resourceId,
            String beforeSummary,
            String afterSummary) {
        request.setAttribute(ATTRIBUTE, new Details(resourceId, beforeSummary, afterSummary));
    }

    static Details details(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof Details details ? details : new Details(null, null, null);
    }

    /** idol 摘要不含头像、简介实际内容；对应字段变更时只记录“已修改”标记。 */
    public static String idolSummary(Map<String, Object> idol, boolean avatarChanged, boolean bioChanged) {
        String summary = "名称：" + text(idol.get("name")) + "；状态：" + enabled(idol.get("enabled"));
        if (avatarChanged) summary += "；头像：已修改";
        if (bioChanged) summary += "；简介：已修改";
        return summary;
    }

    /** 动态源摘要故意不含 RSS 地址，避免 URL 查询参数携带 token 时泄密。 */
    public static String sourceSummary(Map<String, Object> source, boolean urlChanged) {
        String summary = "名称：" + text(source.get("displayName"))
                + "；渠道：" + text(source.get("channel"))
                + "；状态：" + enabled(source.get("enabled"));
        return urlChanged ? summary + "；抓取地址：已修改" : summary;
    }

    public static String requestSummary(Map<String, Object> request) {
        String summary = "名称：" + text(request.get("displayName"))
                + "；状态：" + requestStatus(request.get("status"));
        Object idolId = request.get("approvedIdolId");
        return idolId == null ? summary : summary + "；关联 idol：" + idolId;
    }

    public static String deliverySummary(String postId, String status) {
        return "动态：" + text(postId) + "；结果：" + text(status);
    }

    private static String text(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static String enabled(Object value) {
        return Boolean.TRUE.equals(value) ? "启用" : "停用";
    }

    private static String requestStatus(Object value) {
        return switch (text(value)) {
            case "pending" -> "待审核";
            case "approved" -> "已通过";
            case "rejected" -> "已驳回";
            default -> text(value);
        };
    }

    record Details(String resourceId, String beforeSummary, String afterSummary) {
    }
}
