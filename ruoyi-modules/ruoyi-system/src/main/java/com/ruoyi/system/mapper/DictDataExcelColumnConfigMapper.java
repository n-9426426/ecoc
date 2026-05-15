package com.ruoyi.system.mapper;

import com.ruoyi.system.api.domain.ExcelColumnConfig;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DictDataExcelColumnConfigMapper {

    int insert(ExcelColumnConfig config);

    int batchInsert(@Param("list") List<ExcelColumnConfig> list);

    int deleteByEntityAndTableNameAndFieldName(@Param("tableName") String tableName,
                                               @Param("fieldName") String fieldName,
                                               @Param("entityList") List<String> entityList);

    List<ExcelColumnConfig> selectOneByEntityAndTableNameAndFieldName(@Param("tableName") String tableName,
                                                                @Param("fieldName") String fieldName,
                                                                @Param("entityList") List<String> entityList);
}