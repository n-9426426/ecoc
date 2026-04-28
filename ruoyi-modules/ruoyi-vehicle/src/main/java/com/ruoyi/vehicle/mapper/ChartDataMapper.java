package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.AbnormalStatisticsVo;
import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;
import com.ruoyi.vehicle.domain.vo.VehicleModelVo;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 统计图表
 */
public interface ChartDataMapper {
    // 主页XML汇总图表
    List<ChartDataXmlTotalVo> selectXmlTotal(Integer year);

    List<ChartDataXmlTotalVo> selectXmlValidateTotal(Integer year);

    List<VehicleModelVo> selectVehicleModelVoList(@Param("year") Integer year, @Param("month") Integer month);

    Integer selectStatisticsXmlTotal(ChartDataStatisticsDto statisticsDto);

    Integer selectStatisticsXmlPassNumber(ChartDataStatisticsDto statisticsDto);

    Integer selectStatisticsVehicleWaitNumber(@Param("startTime") LocalDateTime start, @Param("endTime") LocalDateTime end);

    Integer selectStatisticsVehicleFailNumber(ChartDataStatisticsDto statisticsDto);

    Integer selectStatisticsXmlRejectNumber(ChartDataStatisticsDto statisticsDto);

    List<ChartDataXmlTotalVo> selectStatisticsTrend(ChartDataStatisticsDto statisticsDto);

    List<AbnormalStatisticsVo> selectStatisticsAbnormal(ChartDataStatisticsDto statisticsDto);
}