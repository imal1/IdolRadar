package com.idolradar.admin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 为管理端写接口声明稳定、可读的审计业务语义。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAuditOperation {
    String action();

    String resourceType();

    String resourceIdVariable() default "";
}
