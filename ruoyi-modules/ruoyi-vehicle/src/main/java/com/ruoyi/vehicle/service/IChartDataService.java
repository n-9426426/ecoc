package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.CalendarDayVo;
import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;
import com.ruoyi.vehicle.domain.vo.VehicleModelVo;
import com.ruoyi.vehicle.domain.vo.VinCalendarVo;

import java.util.List;
import java.util.Map;

public interface IChartDataService {
    List<ChartDataXmlTotalVo> xmlTotal(Integer year);

    List<ChartDataXmlTotalVo> xmlValidate(Integer year);

    List<VehicleModelVo> vehicleModel(Integer year, Integer month);

    Map<String, Object> statisticsCard(ChartDataStatisticsDto statisticsDto);

    List<ChartDataXmlTotalVo> statisticsTrend(ChartDataStatisticsDto statisticsDto);

    Map<String, Object> statisticsXml(Integer year);

    List<VinCalendarVo> getAllVinCalendarByMonth(int year, int month);

    List<CalendarDayVo> getCalendarByMonth(String vin, int year, int month);
}
