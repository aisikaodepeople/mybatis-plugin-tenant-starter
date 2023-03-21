package com.mybatis.plugin.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TenantProperties.class)
@ConditionalOnClass(ITenantDefine.class)
@ConditionalOnProperty(prefix = "mybatis.tenant.plugin", name = "enable", havingValue = "true")
public class TenantPluginAutoConfiguration {

    @Autowired
    private TenantProperties tenantProperties;

    @Bean
    @ConditionalOnMissingBean(ITenantDefine.class)
    public TenantPluginContext tenantPluginContext() {
        return new TenantPluginContext(tenantProperties);
    }

}
