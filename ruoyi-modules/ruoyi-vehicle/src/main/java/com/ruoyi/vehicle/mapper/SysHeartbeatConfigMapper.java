package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.SysHeartbeatConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 心跳监测配置 Mapper
 */
@Mapper
public interface SysHeartbeatConfigMapper {

    /**
     * 按国家查询配置；若无专属配置则返回全局默认（country_code IS NULL）
     */
    SysHeartbeatConfig selectByCountryOrDefault(@Param("countryCode") String countryCode);

    /**
     * 新增配置
     */
    int insert(SysHeartbeatConfig config);

    /**
     * 更新配置
     */
    int updateById(SysHeartbeatConfig config);
}
