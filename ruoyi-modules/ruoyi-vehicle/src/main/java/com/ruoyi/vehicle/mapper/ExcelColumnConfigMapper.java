package com.ruoyi.vehicle.mapper;

import com.ruoyi.system.api.domain.ExcelColumnConfig;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ExcelColumnConfigMapper {

    /**
     * 根据表名查询列配置
     */
    List<ExcelColumnConfig> selectByTableName(@Param("tableName") String tableName);
}