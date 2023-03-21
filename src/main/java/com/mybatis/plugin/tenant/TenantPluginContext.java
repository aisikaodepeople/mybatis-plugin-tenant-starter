package com.mybatis.plugin.tenant;

import net.sf.jsqlparser.schema.Column;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.io.ResolverUtil;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.substringBeforeLast;

/**
 * 多租户插件的上下文
 * <p>
 * 1. 扫描多租户插件的配置
 * 2. 扫描mapper文件
 * </p>
 */
public class TenantPluginContext {

    /*忽略的mapper类*/
    private static Set<String> TENANT_IGNORE_MAPPER_CLASS_CACHE = new HashSet<>();
    /*忽略的mapper方法*/
    private static Set<String> TENANT_IGNORE_MAPPER_METHOD_CACHE = new HashSet<>();
    /*忽略的表*/
    private static Set<String> TENANT_IGNORE_TABLE_CACHE = new HashSet<>();

    private static TenantProperties tenantProperties;

    public TenantPluginContext(TenantProperties tenantProperties) {

        TenantPluginContext.tenantProperties = tenantProperties;

        if (!tenantProperties.getEnable()) {
            return;
        }

        //扫描需要忽略的mapper类或mapper方法
        String[] basePackages = StringUtils.split(tenantProperties.getMapperLocations(), ",");
        for (String packageName : basePackages) {
            ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
            resolverUtil.find(new ResolverUtil.IsA(Object.class), packageName);

            //mapper类集合
            Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
            for (Class<?> mapperClass : mapperSet) {

                //判断是否忽略该mapper类
                if (AnnotationUtils.findAnnotation(mapperClass, IgnoreTenant.class) != null) {
                    TENANT_IGNORE_MAPPER_CLASS_CACHE.add(mapperClass.getCanonicalName());
                }

                //mapper方法集合
                Method[] mapperMethods = mapperClass.getMethods();
                for (Method method : mapperMethods) {

                    //判断是否忽略该mapper方法
                    if (AnnotationUtils.findAnnotation(method, IgnoreTenant.class) != null) {
                        TENANT_IGNORE_MAPPER_METHOD_CACHE.add(mapperClass.getCanonicalName() + "." + method.getName());
                    }
                }

                //忽略主键查询的mapper方法
                TENANT_IGNORE_MAPPER_METHOD_CACHE.add(mapperClass.getCanonicalName() + ".selectByPrimaryKey");

                //忽略主键删除的mapper方法
                TENANT_IGNORE_MAPPER_METHOD_CACHE.add(mapperClass.getCanonicalName() + ".deleteByPrimaryKey");

                //忽略主键更新的mapper方法
                TENANT_IGNORE_MAPPER_METHOD_CACHE.add(mapperClass.getCanonicalName() + ".updateByPrimaryKeySelective");

            }
        }

        //扫描需要忽略的表
        if (StringUtils.isNotBlank(tenantProperties.getIgnoreTables())) {
            String[] Tables = StringUtils.split(tenantProperties.getIgnoreTables(), ",");
            for (String table : Tables) {
                TENANT_IGNORE_TABLE_CACHE.add(table);
            }
        }

    }

    /**
     * 是否忽略mapper
     *
     * @param mapperId
     * @return
     */
    public static boolean willIgnoreMapper(String mapperId) {
        return TENANT_IGNORE_MAPPER_METHOD_CACHE.contains(mapperId) || TENANT_IGNORE_MAPPER_CLASS_CACHE.contains(substringBeforeLast(mapperId, "."));
    }

    /**
     * 是否忽略表
     *
     * @param tableName
     * @return
     */
    public static boolean willIgnoreTable(String tableName) {
        return TENANT_IGNORE_TABLE_CACHE.stream().anyMatch(t -> t.equalsIgnoreCase(tableName));
    }

    /**
     * 插入时已指定租户字段,插件不在拦截
     *
     * @param columns        插入字段
     * @param tenantIdColumn 租户ID字段
     * @return
     */
    public static boolean ignoreInsert(List<Column> columns, String tenantIdColumn) {
        return columns.stream().map(Column::getColumnName).anyMatch(i -> i.equalsIgnoreCase(tenantIdColumn));
    }

    /**
     * 获取租户数据库字段名
     *
     * @return 租户字段名
     */
    public static String getTenantColumn() {
        return tenantProperties.getColumn();
    }

    /**
     * 是否启用多租户
     *
     * @return
     */
    public static boolean isTenantPluginEnable() {
        return tenantProperties != null && tenantProperties.getEnable();
    }

}
