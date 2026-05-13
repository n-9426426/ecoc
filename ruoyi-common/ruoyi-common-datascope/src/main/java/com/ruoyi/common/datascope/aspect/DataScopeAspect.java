package com.ruoyi.common.datascope.aspect;

import com.ruoyi.common.core.constant.UserConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.ruoyi.common.datascope.annotation.DataScope;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.domain.SysRole;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据过滤处理
 *
 * @author ruoyi
 */
@Aspect
@Component
public class DataScopeAspect
{
    /**
     * 全部数据权限
     */
    public static final String DATA_SCOPE_ALL = "1";

    /**
     * 自定数据权限
     */
    public static final String DATA_SCOPE_CUSTOM = "2";

    /**
     * 部门数据权限
     */
    public static final String DATA_SCOPE_DEPT = "3";

    /**
     * 部门及以下数据权限
     */
    public static final String DATA_SCOPE_DEPT_AND_CHILD = "4";

    /**
     * 仅本人数据权限
     */
    public static final String DATA_SCOPE_SELF = "5";

    /**
     * 数据权限过滤关键字
     */
    public static final String DATA_SCOPE = "dataScope";

    @Before("@annotation(controllerDataScope)")
    public void doBefore(JoinPoint point, DataScope controllerDataScope) throws Throwable {
        clearDataScope(point);
        handleDataScope(point, controllerDataScope);
    }

    protected void handleDataScope(final JoinPoint joinPoint, DataScope controllerDataScope) {
        // 获取当前的用户
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNotNull(loginUser)) {
            SysUser currentUser = loginUser.getSysUser();
            // 如果是超级管理员，则不过滤数据
            if (StringUtils.isNotNull(currentUser) && !currentUser.isAdmin()) {
                // ★ 新增：有 tableAlias 时走工厂+国家子查询过滤，否则走原有部门维度
                if (StringUtils.isNotBlank(controllerDataScope.tableAlias())) {
                    factoryScopeFilter(joinPoint, currentUser, controllerDataScope.tableAlias());
                } else {
                    // 原有部门维度过滤逻辑，完全不动
                    String permission = StringUtils.defaultIfEmpty(
                            controllerDataScope.permission(),
                            SecurityContextHolder.getPermission());
                    dataScopeFilter(joinPoint, currentUser,
                            controllerDataScope.deptAlias(),
                            controllerDataScope.userAlias(),
                            permission);
                }
            }
        }
    }

    // =====================================================
    // ★ 新增：工厂+国家子查询维度数据过滤
    // =====================================================

    /**
     * 工厂+国家维度数据过滤。
     *
     * sys_post_auth 表结构：
     *   post_id      BIGINT       -- 岗位ID
     *   factory_code VARCHAR      -- 工厂dict_code，逗号拼接，如 "101,102"
     *   country      VARCHAR      -- 国家dict_code，逗号拼接，如 "1,3"
     *
     * v 表中 factory_code / country 均为单个 BIGINT 值。
     * 使用 FIND_IN_SET 判断 v 表的值是否在权限字符串中。
     *
     * 生成 SQL 示例（用户岗位ID为 1,2）：
     * AND EXISTS (
     *   SELECT 1 FROM sys_post_auth
     *   WHERE post_id IN (1,2)
     *     AND FIND_IN_SET(v.factory_code, factory_code)
     *     AND FIND_IN_SET(v.country, country)
     * )
     *
     * @param joinPoint  切点
     * @param user       当前用户
     * @param tableAlias 业务表别名，如 "v"
     */
    private void factoryScopeFilter(JoinPoint joinPoint, SysUser user, String tableAlias)
    {
        StringBuilder sqlString = new StringBuilder();
        String alias = tableAlias + ".";
        Long userId = user.getUserId();

        // ★ 根据表别名确定车型字段名
        String modelField = "";
        switch (tableAlias) {
            case "vi":
                modelField = alias + "vehicle_model";
                break;
            case "xl":
                modelField = alias + "model_code";
                break;
            case "vt":
                modelField = alias + "model_no";
                break;
            case "xt":
                modelField = alias + "model_dict_code";
                break;
            default:
                break;
        }

        sqlString.append(" AND EXISTS (")
                .append("SELECT 1 FROM sys_post_auth spa")
                .append(" INNER JOIN sys_user_post sup ON spa.post_id = sup.post_id")
                .append(" WHERE sup.user_id = ").append(userId);
        if (!tableAlias.equals("vt") && !tableAlias.equals("xt")) {
            sqlString.append(" AND (spa.factory_code IS NULL OR spa.factory_code = '' OR FIND_IN_SET(").append(alias).append("factory_code, spa.factory_code))");
        }
        sqlString.append(" AND (spa.country IS NULL OR spa.country = '' OR FIND_IN_SET(").append(alias).append("country, spa.country))");
        if (StringUtils.isNotBlank(modelField)) {
            sqlString.append(" AND (spa.model_code IS NULL OR spa.model_code = '' OR FIND_IN_SET(").append(modelField).append(", spa.model_code))");
        }
        sqlString.append(")");

        Object params = joinPoint.getArgs()[0];
        if (StringUtils.isNotNull(params) && params instanceof BaseEntity)
        {
            BaseEntity baseEntity = (BaseEntity) params;
            baseEntity.getParams().put(DATA_SCOPE, sqlString.toString());
        }
    }

    // =====================================================
    // 原有部门维度过滤，完全不动
    // =====================================================

    /**
     * 数据范围过滤
     *
     * @param joinPoint  切点
     * @param user       用户
     * @param deptAlias  部门别名
     * @param userAlias  用户别名
     * @param permission 权限字符
     */
    public static void dataScopeFilter(JoinPoint joinPoint, SysUser user,
                                       String deptAlias, String userAlias, String permission)
    {
        StringBuilder sqlString = new StringBuilder();
        List<String> conditions = new ArrayList<String>();
        List<String> scopeCustomIds = new ArrayList<String>();
        user.getRoles().forEach(role -> {
            if (DATA_SCOPE_CUSTOM.equals(role.getDataScope())
                    && StringUtils.equals(role.getStatus(), UserConstants.ROLE_NORMAL)
                    && (StringUtils.isEmpty(permission)
                    || StringUtils.containsAny(role.getPermissions(), Convert.toStrArray(permission))))
            {
                scopeCustomIds.add(Convert.toStr(role.getRoleId()));
            }
        });

        for (SysRole role : user.getRoles())
        {
            String dataScope = role.getDataScope();
            if (conditions.contains(dataScope)
                    || StringUtils.equals(role.getStatus(), UserConstants.ROLE_DISABLE))
            {
                continue;
            }
            if (StringUtils.isNotEmpty(permission)
                    && !StringUtils.containsAny(role.getPermissions(), Convert.toStrArray(permission)))
            {
                continue;
            }
            if (DATA_SCOPE_ALL.equals(dataScope))
            {
                sqlString = new StringBuilder();
                conditions.add(dataScope);
                break;
            }
            else if (DATA_SCOPE_CUSTOM.equals(dataScope))
            {
                if (scopeCustomIds.size() > 1)
                {
                    sqlString.append(StringUtils.format(
                            " OR {}.dept_id IN ( SELECT dept_id FROM sys_role_dept WHERE role_id in ({}) ) ",
                            deptAlias, String.join(",", scopeCustomIds)));
                }
                else
                {
                    sqlString.append(StringUtils.format(
                            " OR {}.dept_id IN ( SELECT dept_id FROM sys_role_dept WHERE role_id = {} ) ",
                            deptAlias, role.getRoleId()));
                }
            }
            else if (DATA_SCOPE_DEPT.equals(dataScope))
            {
                sqlString.append(StringUtils.format(
                        " OR {}.dept_id = {} ", deptAlias, user.getDeptId()));
            }
            else if (DATA_SCOPE_DEPT_AND_CHILD.equals(dataScope))
            {
                sqlString.append(StringUtils.format(
                        " OR {}.dept_id IN ( SELECT dept_id FROM sys_dept WHERE dept_id = {} or find_in_set( {} , ancestors ) )",
                        deptAlias, user.getDeptId(), user.getDeptId()));
            }
            else if (DATA_SCOPE_SELF.equals(dataScope))
            {
                if (StringUtils.isNotBlank(userAlias))
                {
                    sqlString.append(StringUtils.format(
                            " OR {}.user_id = {} ", userAlias, user.getUserId()));
                }
                else
                {
                    // 数据权限为仅本人且没有userAlias别名不查询任何数据
                    sqlString.append(StringUtils.format(
                            " OR {}.dept_id = 0 ", deptAlias));
                }
            }
            conditions.add(dataScope);
        }

        // 角色都不包含传递过来的权限字符，这个时候sqlString也会为空，所以要限制一下,不查询任何数据
        if (StringUtils.isEmpty(conditions))
        {
            sqlString.append(StringUtils.format(" OR {}.dept_id = 0 ", deptAlias));
        }

        if (StringUtils.isNotBlank(sqlString.toString()))
        {
            Object params = joinPoint.getArgs()[0];
            if (StringUtils.isNotNull(params) && params instanceof BaseEntity)
            {
                BaseEntity baseEntity = (BaseEntity) params;
                baseEntity.getParams().put(DATA_SCOPE, " AND (" + sqlString.substring(4) + ")");
            }
        }
    }

    /**
     * 拼接权限sql前先清空params.dataScope参数防止注入
     */
    private void clearDataScope(final JoinPoint joinPoint)
    {
        Object params = joinPoint.getArgs()[0];
        if (StringUtils.isNotNull(params) && params instanceof BaseEntity)
        {
            BaseEntity baseEntity = (BaseEntity) params;
            baseEntity.getParams().put(DATA_SCOPE, "");
        }
    }
}