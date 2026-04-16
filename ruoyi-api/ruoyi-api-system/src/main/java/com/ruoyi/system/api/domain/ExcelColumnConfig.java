package com.ruoyi.system.api.domain;

import lombok.Data;

@Data
public class ExcelColumnConfig {

    private Long id;

    /** 对应业务表名 */
    private String tableName;

    /** Java 字段名 */
    private String fieldName;

    /** Excel 列头（中文） */
    private String columnNameZhCn;

    /** Excel 列头（英文） */
    private String columnNameEnUs;

    /** 列顺序 */
    private Integer sort;

    /** 是否启用（1=启用 0=禁用） */
    private Integer enabled;

    /**
     * 根据语言参数返回对应列头
     *
     * @param lang zh_CN 或 en_US
     * @return 对应语言的列头名称
     */
    public String getColumnName(String lang) {
        if ("en_US".equalsIgnoreCase(lang)) {
            return columnNameEnUs;
        }
        return columnNameZhCn;
    }
}