package com.mybatis.plugin.tenant;

/**
 * 租户插件异常类
 */
public class TenantPluginException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantPluginException(String message) {
        super(message);
    }

    public TenantPluginException(String message, Throwable throwable) {
        super(message, throwable);
    }

    public static TenantPluginException tenantPluginException(String msg, Throwable t, Object... params) {
        return new TenantPluginException(String.format(msg, params), t);
    }

    public static TenantPluginException tenantPluginException(String msg, Object... params) {
        return new TenantPluginException(String.format(msg, params));
    }

}
