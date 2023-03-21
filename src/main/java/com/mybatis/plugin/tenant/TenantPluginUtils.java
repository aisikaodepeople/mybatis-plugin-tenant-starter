package com.mybatis.plugin.tenant;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 多租户插件工具类
 */
public class TenantPluginUtils {

    public static <T> T realTarget(Object target) {
        if (Proxy.isProxyClass(target.getClass())) {
            MetaObject metaObject = SystemMetaObject.forObject(target);
            return realTarget(metaObject.getValue("h.target"));
        }
        return (T) target;
    }

    public static void setAdditionalParameter(BoundSql boundSql, Map<String, Object> additionalParameters) {
        additionalParameters.forEach(boundSql::setAdditionalParameter);
    }

    public static MPBoundSql mpBoundSql(BoundSql boundSql) {
        return new MPBoundSql(boundSql);
    }

    public static MPStatementHandler mpStatementHandler(StatementHandler statementHandler) {
        statementHandler = realTarget(statementHandler);
        MetaObject object = SystemMetaObject.forObject(statementHandler);
        return new MPStatementHandler(SystemMetaObject.forObject(object.getValue("delegate")));
    }

    public static class MPStatementHandler {
        private final MetaObject statementHandler;

        MPStatementHandler(MetaObject statementHandler) {
            this.statementHandler = statementHandler;
        }

        public ParameterHandler parameterHandler() {
            return get("parameterHandler");
        }

        public MappedStatement mappedStatement() {
            return get("mappedStatement");
        }

        public Executor executor() {
            return get("executor");
        }

        public MPBoundSql mPBoundSql() {
            return new MPBoundSql(boundSql());
        }

        public BoundSql boundSql() {
            return get("boundSql");
        }

        public Configuration configuration() {
            return get("configuration");
        }

        private <T> T get(String property) {
            return (T) statementHandler.getValue(property);
        }
    }

    public static class MPBoundSql {
        private final MetaObject boundSql;
        private final BoundSql delegate;

        MPBoundSql(BoundSql boundSql) {
            this.delegate = boundSql;
            this.boundSql = SystemMetaObject.forObject(boundSql);
        }

        public String sql() {
            return delegate.getSql();
        }

        public void sql(String sql) {
            boundSql.setValue("sql", sql);
        }

        public List<ParameterMapping> parameterMappings() {
            List<ParameterMapping> parameterMappings = delegate.getParameterMappings();
            return new ArrayList<>(parameterMappings);
        }

        public void parameterMappings(List<ParameterMapping> parameterMappings) {
            boundSql.setValue("parameterMappings", Collections.unmodifiableList(parameterMappings));
        }

        public Object parameterObject() {
            return get("parameterObject");
        }

        public Map<String, Object> additionalParameters() {
            return get("additionalParameters");
        }

        private <T> T get(String property) {
            return (T) boundSql.getValue(property);
        }
    }
    
}
