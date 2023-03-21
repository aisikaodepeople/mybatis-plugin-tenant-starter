package com.mybatis.plugin.tenant;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多租户插件拦截器
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = StatementHandler.class, method = "getBoundSql", args = {}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
})
public class TenantPluginInterceptor implements Interceptor {

    private ITenantDefine tenantDefine;

    public TenantPluginInterceptor(ITenantDefine tenantDefine) {
        this.tenantDefine = tenantDefine;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {

        //多租户插件未启用
        if (!TenantPluginContext.isTenantPluginEnable()) {
            return invocation.proceed();
        }

        Object target = invocation.getTarget();
        Object[] args = invocation.getArgs();

        if (target instanceof Executor) {
            final Executor executor = (Executor) target;
            Object parameter = args[1];
            boolean isUpdate = args.length == 2;
            MappedStatement ms = (MappedStatement) args[0];
            if (!isUpdate && ms.getSqlCommandType() == SqlCommandType.SELECT) {
                RowBounds rowBounds = (RowBounds) args[2];
                ResultHandler resultHandler = (ResultHandler) args[3];
                BoundSql boundSql;

                if (args.length == 4) {
                    boundSql = ms.getBoundSql(parameter);
                } else {
                    boundSql = (BoundSql) args[5];
                }

                if (!TenantPluginContext.willIgnoreMapper(ms.getId())) {
                    TenantPluginUtils.MPBoundSql mpBs = TenantPluginUtils.mpBoundSql(boundSql);
                    mpBs.sql(parserSingle(mpBs.sql(), null));
                }

                CacheKey cacheKey = executor.createCacheKey(ms, parameter, rowBounds, boundSql);
                return executor.query(ms, parameter, rowBounds, resultHandler, cacheKey, boundSql);
            }
        } else {
            final StatementHandler statementHandler = (StatementHandler) target;

            if (args != null) {
                TenantPluginUtils.MPStatementHandler mpStatementHandler = TenantPluginUtils.mpStatementHandler(statementHandler);
                MappedStatement ms = mpStatementHandler.mappedStatement();
                SqlCommandType sct = ms.getSqlCommandType();

                if (sct == SqlCommandType.INSERT || sct == SqlCommandType.UPDATE || sct == SqlCommandType.DELETE) {
                    if (!TenantPluginContext.willIgnoreMapper(ms.getId())) {
                        TenantPluginUtils.MPBoundSql mpBs = mpStatementHandler.mPBoundSql();
                        mpBs.sql(parserMulti(mpBs.sql(), null));
                    }
                }
            }

        }

        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof Executor || target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }

    @Override
    public void setProperties(Properties properties) {
        //do nothing
    }

    /**
     * SQL解析
     *
     * @param statement JsqlParser Statement
     * @param index     Statement index
     * @param sql       原sql
     * @param obj       mapperId
     * @return 解析后的sql
     */
    public String processParser(Statement statement, int index, String sql, Object obj) {
        if (statement instanceof Insert) {
            processInsert((Insert) statement, index, sql, obj);
        } else if (statement instanceof Select) {
            processSelect((Select) statement, index, sql, obj);
        } else if (statement instanceof Update) {
            processUpdate((Update) statement, index, sql, obj);
        } else if (statement instanceof Delete) {
            processDelete((Delete) statement, index, sql, obj);
        }
        sql = statement.toString();
        return sql;
    }

    /**
     * 新增
     *
     * @param insert
     * @param index
     * @param sql
     * @param obj
     */
    public void processInsert(Insert insert, int index, String sql, Object obj) {
        if (TenantPluginContext.willIgnoreTable(insert.getTable().getName())) {
            return;
        }
        List<Column> columns = insert.getColumns();
        if (CollectionUtils.isEmpty(columns)) {
            //不给列名的insert不处理
            return;
        }
        String tenantIdColumn = TenantPluginContext.getTenantColumn();
        if (TenantPluginContext.ignoreInsert(columns, tenantIdColumn)) {
            //已给出租户列的insert不处理
            return;
        }
        columns.add(new Column(tenantIdColumn));

        List<Expression> duplicateUpdateColumns = insert.getDuplicateUpdateExpressionList();
        if (CollectionUtils.isNotEmpty(duplicateUpdateColumns)) {
            EqualsTo equalsTo = new EqualsTo();
            equalsTo.setLeftExpression(new StringValue(tenantIdColumn));
            equalsTo.setRightExpression(tenantDefine.getTenantId());
            duplicateUpdateColumns.add(equalsTo);
        }

        Select select = insert.getSelect();
        if (select != null) {
            this.processInsertSelect(select.getSelectBody());
        } else if (insert.getItemsList() != null) {
            ItemsList itemsList = insert.getItemsList();
            if (itemsList instanceof MultiExpressionList) {
                ((MultiExpressionList) itemsList).getExpressionLists().forEach(el -> el.getExpressions().add(tenantDefine.getTenantId()));
            } else {
                ((ExpressionList) itemsList).getExpressions().add(tenantDefine.getTenantId());
            }
        } else {
            throw TenantPluginException.tenantPluginException("Failed to process multiple-table update, please exclude the tableName or statementId");
        }
    }

    /**
     * delete 语句处理
     *
     * @param delete
     * @param index
     * @param sql
     * @param obj
     */
    public void processDelete(Delete delete, int index, String sql, Object obj) {
        if (TenantPluginContext.willIgnoreTable(delete.getTable().getName())) {
            return;
        }
        delete.setWhere(andExpression(delete.getTable(), delete.getWhere()));
    }

    /**
     * update 语句处理
     *
     * @param update
     * @param index
     * @param sql
     * @param obj
     */
    public void processUpdate(Update update, int index, String sql, Object obj) {
        final Table table = update.getTable();
        if (TenantPluginContext.willIgnoreTable(table.getName())) {
            return;
        }
        update.setWhere(this.andExpression(table, update.getWhere()));
    }

    /**
     * 查询
     *
     * @param select
     * @param index
     * @param sql
     * @param obj
     */
    public void processSelect(Select select, int index, String sql, Object obj) {
        processSelectBody(select.getSelectBody());
        List<WithItem> withItemsList = select.getWithItemsList();
        if (!CollectionUtils.isEmpty(withItemsList)) {
            withItemsList.forEach(this::processSelectBody);
        }
    }

    public String parserSingle(String sql, Object obj) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return processParser(statement, 0, sql, obj);
        } catch (JSQLParserException e) {
            throw TenantPluginException.tenantPluginException("SQL解析失败: %s", e.getCause(), sql);
        }
    }

    public String parserMulti(String sql, Object obj) {
        try {
            StringBuilder sb = new StringBuilder();
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            int i = 0;
            for (Statement statement : statements.getStatements()) {
                if (i > 0) {
                    sb.append(StringPool.SEMICOLON);
                }
                sb.append(processParser(statement, i, sql, obj));
                i++;
            }
            return sb.toString();
        } catch (JSQLParserException e) {
            throw TenantPluginException.tenantPluginException("SQL解析失败: %s", e.getCause(), sql);
        }
    }

    /**
     * 处理 insert into select,进入这里表示需要insert的表启用了多租户,则select的表都启动了
     *
     * @param selectBody SelectBody
     */
    public void processInsertSelect(SelectBody selectBody) {
        PlainSelect plainSelect = (PlainSelect) selectBody;
        FromItem fromItem = plainSelect.getFromItem();
        if (fromItem instanceof Table) {
            processPlainSelect(plainSelect);
            appendSelectItem(plainSelect.getSelectItems());
        } else if (fromItem instanceof SubSelect) {
            SubSelect subSelect = (SubSelect) fromItem;
            appendSelectItem(plainSelect.getSelectItems());
            processInsertSelect(subSelect.getSelectBody());
        }
    }

    public void appendSelectItem(List<SelectItem> selectItems) {
        if (CollectionUtils.isEmpty(selectItems)) {
            return;
        }
        if (selectItems.size() == 1) {
            SelectItem item = selectItems.get(0);
            if (item instanceof AllColumns || item instanceof AllTableColumns) {
                return;
            }
        }
        selectItems.add(new SelectExpressionItem(new Column(TenantPluginContext.getTenantColumn())));
    }

    public BinaryExpression andExpression(Table table, Expression where) {
        //获得where条件表达式
        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(this.getAliasColumn(table));
        equalsTo.setRightExpression(tenantDefine.getTenantId());
        if (null != where) {
            if (where instanceof OrExpression) {
                return new AndExpression(equalsTo, new Parenthesis(where));
            } else {
                return new AndExpression(equalsTo, where);
            }
        }
        return equalsTo;
    }

    public void processSelectBody(SelectBody selectBody) {
        if (selectBody == null) {
            return;
        }
        if (selectBody instanceof PlainSelect) {
            processPlainSelect((PlainSelect) selectBody);
        } else if (selectBody instanceof WithItem) {
            WithItem withItem = (WithItem) selectBody;
            processSelectBody(withItem.getSubSelect().getSelectBody());
        } else {
            SetOperationList operationList = (SetOperationList) selectBody;
            List<SelectBody> selectBodys = operationList.getSelects();
            if (CollectionUtils.isNotEmpty(selectBodys)) {
                selectBodys.forEach(this::processSelectBody);
            }
        }
    }

    /**
     * 处理 PlainSelect
     */
    public void processPlainSelect(PlainSelect plainSelect) {
        List<SelectItem> selectItems = plainSelect.getSelectItems();
        if (CollectionUtils.isNotEmpty(selectItems)) {
            selectItems.forEach(this::processSelectItem);
        }

        //处理where中的子查询
        Expression where = plainSelect.getWhere();
        processWhereSubSelect(where);

        //处理fromItem
        FromItem fromItem = plainSelect.getFromItem();
        List<Table> list = processFromItem(fromItem);
        List<Table> mainTables = new ArrayList<>(list);

        //处理join
        List<Join> joins = plainSelect.getJoins();
        if (CollectionUtils.isNotEmpty(joins)) {
            mainTables = processJoins(mainTables, joins);
        }

        //当有mainTable时,进行where条件追加
        if (CollectionUtils.isNotEmpty(mainTables)) {
            plainSelect.setWhere(builderExpression(where, mainTables));
        }
    }

    /**
     * 处理select
     *
     * @param selectItem
     */
    public void processSelectItem(SelectItem selectItem) {
        if (selectItem instanceof SelectExpressionItem) {
            SelectExpressionItem selectExpressionItem = (SelectExpressionItem) selectItem;
            if (selectExpressionItem.getExpression() instanceof SubSelect) {
                processSelectBody(((SubSelect) selectExpressionItem.getExpression()).getSelectBody());
            } else if (selectExpressionItem.getExpression() instanceof Function) {
                processFunction((Function) selectExpressionItem.getExpression());
            }
        }
    }

    /**
     * 处理函数
     * <p>
     * 支持:
     * 1. select fun(args..)
     * 2. select fun1(fun2(args..),args..)
     * <p>
     *
     * @param function
     */
    public void processFunction(Function function) {
        ExpressionList parameters = function.getParameters();
        if (parameters != null) {
            parameters.getExpressions().forEach(expression -> {
                if (expression instanceof SubSelect) {
                    processSelectBody(((SubSelect) expression).getSelectBody());
                } else if (expression instanceof Function) {
                    processFunction((Function) expression);
                }
            });
        }
    }

    /**
     * 处理where条件内的子查询
     * <p>
     * 支持如下:
     * 1. in
     * 2. =
     * 3. >
     * 4. <
     * 5. >=
     * 6. <=
     * 7. <>
     * 8. EXISTS
     * 9. NOT EXISTS
     * <p>
     * 前提条件:
     * 1. 子查询必须放在小括号中
     * 2. 子查询一般放在比较操作符的右边
     *
     * @param where where 条件
     */
    public void processWhereSubSelect(Expression where) {
        if (where == null) {
            return;
        }
        if (where instanceof FromItem) {
            processOtherFromItem((FromItem) where);
            return;
        }
        if (where.toString().indexOf("SELECT") > 0) {
            //有子查询
            if (where instanceof BinaryExpression) {
                //比较符号and,or等等
                BinaryExpression expression = (BinaryExpression) where;
                processWhereSubSelect(expression.getLeftExpression());
                processWhereSubSelect(expression.getRightExpression());
            } else if (where instanceof InExpression) {
                //in
                InExpression expression = (InExpression) where;
                Expression inExpression = expression.getRightExpression();
                if (inExpression instanceof SubSelect) {
                    processSelectBody(((SubSelect) inExpression).getSelectBody());
                }
            } else if (where instanceof ExistsExpression) {
                //exists
                ExistsExpression expression = (ExistsExpression) where;
                processWhereSubSelect(expression.getRightExpression());
            } else if (where instanceof NotExpression) {
                //not exists
                NotExpression expression = (NotExpression) where;
                processWhereSubSelect(expression.getExpression());
            } else if (where instanceof Parenthesis) {
                Parenthesis expression = (Parenthesis) where;
                processWhereSubSelect(expression.getExpression());
            }
        }
    }

    /**
     * 处理子查询
     *
     * @param fromItem
     */
    public void processOtherFromItem(FromItem fromItem) {
        //去除括号
        while (fromItem instanceof ParenthesisFromItem) {
            fromItem = ((ParenthesisFromItem) fromItem).getFromItem();
        }

        if (fromItem instanceof SubSelect) {
            SubSelect subSelect = (SubSelect) fromItem;
            if (subSelect.getSelectBody() != null) {
                processSelectBody(subSelect.getSelectBody());
            }
        } else if (fromItem instanceof ValuesList) {
            //do nothing
        } else if (fromItem instanceof LateralSubSelect) {
            LateralSubSelect lateralSubSelect = (LateralSubSelect) fromItem;
            if (lateralSubSelect.getSubSelect() != null) {
                SubSelect subSelect = lateralSubSelect.getSubSelect();
                if (subSelect.getSelectBody() != null) {
                    processSelectBody(subSelect.getSelectBody());
                }
            }
        }
    }

    /**
     * 处理from
     *
     * @param fromItem
     * @return
     */
    public List<Table> processFromItem(FromItem fromItem) {
        //处理括号括起来的表达式
        while (fromItem instanceof ParenthesisFromItem) {
            fromItem = ((ParenthesisFromItem) fromItem).getFromItem();
        }

        List<Table> mainTables = new ArrayList<>();
        //无join时的处理逻辑
        if (fromItem instanceof Table) {
            Table fromTable = (Table) fromItem;
            if (!TenantPluginContext.willIgnoreTable(fromTable.getName())) {
                mainTables.add(fromTable);
            }
        } else if (fromItem instanceof SubJoin) {
            //SubJoin类型则还需要添加上where条件
            List<Table> tables = processSubJoin((SubJoin) fromItem);
            mainTables.addAll(tables);
        } else {
            //处理下fromItem
            processOtherFromItem(fromItem);
        }
        return mainTables;
    }

    /**
     * 处理sub join
     *
     * @param subJoin subJoin
     * @return 返回subJoin中的主表
     */
    public List<Table> processSubJoin(SubJoin subJoin) {
        List<Table> mainTables = new ArrayList<>();
        if (subJoin.getJoinList() != null) {
            List<Table> list = processFromItem(subJoin.getLeft());
            mainTables.addAll(list);
            mainTables = processJoins(mainTables, subJoin.getJoinList());
        }
        return mainTables;
    }

    /**
     * 处理 joins
     *
     * @param mainTables 可以为 null
     * @param joins      join 集合
     * @return List<Table> 右连接查询的Table列表
     */
    public List<Table> processJoins(List<Table> mainTables, List<Join> joins) {
        if (mainTables == null) {
            mainTables = new ArrayList<>();
        }

        //join表达式中最终的主表
        Table mainTable = null;
        //当前join的左表
        Table leftTable = null;
        if (mainTables.size() == 1) {
            mainTable = mainTables.get(0);
            leftTable = mainTable;
        }

        //对于on表达式写在最后的join,需要记录下前面多个on的表名
        Deque<List<Table>> onTableDeque = new LinkedList<>();
        for (Join join : joins) {
            //处理on表达式
            FromItem joinItem = join.getRightItem();

            //获取当前join的表,subJoint可以看作是一张表
            List<Table> joinTables = null;
            if (joinItem instanceof Table) {
                joinTables = new ArrayList<>();
                joinTables.add((Table) joinItem);
            } else if (joinItem instanceof SubJoin) {
                joinTables = processSubJoin((SubJoin) joinItem);
            }

            if (joinTables != null) {

                //如果是隐式内连接
                if (join.isSimple()) {
                    mainTables.addAll(joinTables);
                    continue;
                }

                //当前表是否忽略
                Table joinTable = joinTables.get(0);
                boolean joinTableNeedIgnore = TenantPluginContext.willIgnoreTable(joinTable.getName());

                List<Table> onTables = null;
                //如果不要忽略,且是右连接,则记录下当前表
                if (join.isRight()) {
                    mainTable = joinTableNeedIgnore ? null : joinTable;
                    if (leftTable != null) {
                        onTables = Collections.singletonList(leftTable);
                    }
                } else if (join.isLeft()) {
                    if (!joinTableNeedIgnore) {
                        onTables = Collections.singletonList(joinTable);
                    }
                } else if (join.isInner()) {
                    if (mainTable == null) {
                        onTables = Collections.singletonList(joinTable);
                    } else {
                        onTables = Arrays.asList(mainTable, joinTable);
                    }
                    mainTable = null;
                }
                mainTables = new ArrayList<>();
                if (mainTable != null) {
                    mainTables.add(mainTable);
                }

                //获取join尾缀的on表达式列表
                Collection<Expression> originOnExpressions = join.getOnExpressions();
                //正常join on表达式只有一个,立刻处理
                if (originOnExpressions.size() == 1 && onTables != null) {
                    List<Expression> onExpressions = new LinkedList<>();
                    onExpressions.add(builderExpression(originOnExpressions.iterator().next(), onTables));
                    join.setOnExpressions(onExpressions);
                    leftTable = joinTable;
                    continue;
                }
                //表名压栈,忽略的表压入null,以便后续不处理
                onTableDeque.push(onTables);
                //尾缀多个on表达式的时候统一处理
                if (originOnExpressions.size() > 1) {
                    Collection<Expression> onExpressions = new LinkedList<>();
                    for (Expression originOnExpression : originOnExpressions) {
                        List<Table> currentTableList = onTableDeque.poll();
                        if (CollectionUtils.isEmpty(currentTableList)) {
                            onExpressions.add(originOnExpression);
                        } else {
                            onExpressions.add(builderExpression(originOnExpression, currentTableList));
                        }
                    }
                    join.setOnExpressions(onExpressions);
                }
                leftTable = joinTable;
            } else {
                processOtherFromItem(joinItem);
                leftTable = null;
            }

        }

        return mainTables;
    }

    /**
     * 处理条件
     *
     * @param currentExpression
     * @param tables
     * @return
     */
    public Expression builderExpression(Expression currentExpression, List<Table> tables) {
        //没有表需要处理直接返回
        if (CollectionUtils.isEmpty(tables)) {
            return currentExpression;
        }
        //租户
        Expression tenantId = tenantDefine.getTenantId();
        //构造每张表的条件
        List<EqualsTo> equalsTos = tables.stream()
                .map(item -> new EqualsTo(getAliasColumn(item), tenantId))
                .collect(Collectors.toList());
        //注入的表达式
        Expression injectExpression = equalsTos.get(0);
        //如果有多表,则用and连接
        if (equalsTos.size() > 1) {
            for (int i = 1; i < equalsTos.size(); i++) {
                injectExpression = new AndExpression(injectExpression, equalsTos.get(i));
            }
        }

        if (currentExpression == null) {
            return injectExpression;
        }
        if (currentExpression instanceof OrExpression) {
            return new AndExpression(new Parenthesis(currentExpression), injectExpression);
        } else {
            return new AndExpression(currentExpression, injectExpression);
        }
    }

    /**
     * 设置租户字段别名
     * <p>tenantId或tableAlias.tenantId</p>
     *
     * @param table
     */
    public Column getAliasColumn(Table table) {
        StringBuilder column = new StringBuilder();
        //为了兼容隐式内连接,没有别名时条件就需要加上表名
        if (table.getAlias() != null) {
            column.append(table.getAlias().getName());
        } else {
            column.append(table.getName());
        }
        column.append(StringPool.DOT).append(TenantPluginContext.getTenantColumn());
        return new Column(column.toString());
    }

}
