package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;
import com.ruoyi.vehicle.domain.vo.VehicleModelVo;

import java.util.List;


/**
 * 统计图表
 */
public interface ChartDataMapper {
    // 主页XML汇总图表
    ChartDataXmlTotalVo selectXmlTotal(Integer year);

    ChartDataXmlTotalVo selectXmlValidateTotal(Integer year);

    List<VehicleModelVo> selectVehicleModelVoList(Integer year, Integer month);

    Integer selectStatisticsXmlTotal(ChartDataStatisticsDto statisticsDto);

    Integer selectStatisticsXmlPassNumber(ChartDataStatisticsDto statisticsDto);

    Integer selectStatisticsVehicleFailNumber(ChartDataStatisticsDto statisticsDto);

    Integer selectStatisticsXmlRejectNumber(ChartDataStatisticsDto statisticsDto);

    ChartDataXmlTotalVo selectStatisticsTrend(ChartDataStatisticsDto statisticsDto);
}