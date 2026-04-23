package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleLifecycle;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface VehicleLifecycleMapper {

    int insert(VehicleLifecycle vehicleLifecycle);

    /**
     * 查询某 vin 在指定时间范围内的所有记录，按时间升序
     * 用于日历展示（圆点逻辑 + 每天当天记录）
     */
    List<VehicleLifecycle> selectByVinAndDateRange(
            @Param("vin") String vin,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * 查询某 vin 截至指定时间最新的一条记录
     * 用于确定当天的 currentOperate（阶段显示逻辑）
     */
    VehicleLifecycle selectLatestBeforeTime(
            @Param("vin") String vin,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * 查询所有不重复的 vin
     */
    List<String> selectAllVins();

    /**
     * 查询截至指定时间，该 vin 下每个 operate 的最新一条记录
     */
    List<VehicleLifecycle> selectLatestPerOperateBeforeTime(
            @Param("vin") String vin,
            @Param("time") LocalDateTime time);
}
