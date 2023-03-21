# MyBatis 租户插件

```

配置文件application.properties

#启用插件,默认不启用
mybatis.tenant.plugin.enable=true
#租户字段
mybatis.tenant.plugin.column=tenantId
#忽略的表,以','分隔
mybatis.tenant.plugin.ignore-tables=t1,t2
#mapper包路径,以','分隔
mybatis.tenant.plugin.mapper-locations=com.xxx.mapper

```

```java

import TenantDefine;
import TenantPluginInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = {"com.xxx.mapper"})
public class MybatisTenantPluginConfiguration {

    @Bean
    public TenantPluginInterceptor tenantPluginInterceptor() {
        return new TenantPluginInterceptor(new TenantDefine() {
            @Override
            public Expression getTenantId() {
                return new StringValue("xxx");
            }
        });
    }

}

```