package com.ruoyi.common.log.enums;

/**
 * 业务操作类型
 * 
 * @author ruoyi
 */
public enum BusinessType
{
    /**
     * 其它
     */
    OTHER,

    /**
     * 新增
     */
    INSERT,

    /**
     * 生成
     */
    CREATE,

    /**
     * 修改
     */
    UPDATE,

    /**
     * 恢复
     */
    RESTORE,

    /**
     * 删除
     */
    DELETE,

    /**
     * 永久删除
     */
    PERMANENTLY_DELETE,

    /**
     * 授权
     */
    GRANT,

    /**
     * 导出
     */
    EXPORT,

    /**
     * 导入
     */
    IMPORT,

    /**
     * 强退
     */
    FORCE,

    /**
     * 清空数据
     */
    CLEAN,
}
