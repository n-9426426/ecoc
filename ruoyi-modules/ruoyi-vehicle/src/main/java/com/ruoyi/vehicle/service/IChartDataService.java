package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;
import com.ruoyi.vehicle.domain.vo.VehicleModelVo;

import java.util.List;
import java.util.Map;

public interface IChartDataService {
    ChartDataXmlTotalVo xmlTotal(Integer year);

    ChartDataXmlTotalVo xmlValidate(Integer year);

    List<VehicleModelVo> vehicleModel(Integer year, Integer month);

    Map<String, Object> statisticsCard(ChartDataStatisticsDto statisticsDto);

    ChartDataXmlTotalVo statisticsTrend(ChartDataStatisticsDto statisticsDto);

}
