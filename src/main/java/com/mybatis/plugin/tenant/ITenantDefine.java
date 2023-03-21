package com.mybatis.plugin.tenant;

import net.sf.jsqlparser.expression.Expression;

/**
 * 获取租户ID接口定义
 */
public interface ITenantDefine {

    /**
     * 获取租户ID
     * <p>
     * 注意:
     * 1.多线程环境下读取不到web上下文环境
     * 2.测试单元中会出现部分mapper方法报错的情况
     * 3.对sql性能有要求的
     * 以上情况不推荐使用多租户插件
     * 解决方法: 自己手动写sql,在对应的mapper方法上添加注解 {@link IgnoreTenant}
     * <p/>
     *
     * @return
     */
    Expression getTenantId();

}
