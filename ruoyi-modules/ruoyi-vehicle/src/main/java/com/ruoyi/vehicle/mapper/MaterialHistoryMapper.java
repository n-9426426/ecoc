package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.MaterialHistory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MaterialHistoryMapper {

    List<MaterialHistory> selectByMaterialId(@Param("materialId") Long materialId, @Param("version") String version);

    int insert(MaterialHistory materialHistory);

    int deleteById(Long id);
}
