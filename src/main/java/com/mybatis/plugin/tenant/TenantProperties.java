package com.mybatis.plugin.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mybatis.tenant.plugin")
public class TenantProperties {

    //插件是否启用
    private Boolean enable = false;

    //租户字段
    private String column = "tenant_id";

    //忽略的表以,分割
    private String ignoreTables;

    //mapper文件位置以,分割
    private String mapperLocations;

}
