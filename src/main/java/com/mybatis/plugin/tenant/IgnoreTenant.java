package com.mybatis.plugin.tenant;

import java.lang.annotation.*;

/**
 * 忽略租户
 */
@Inherited
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IgnoreTenant {
}
