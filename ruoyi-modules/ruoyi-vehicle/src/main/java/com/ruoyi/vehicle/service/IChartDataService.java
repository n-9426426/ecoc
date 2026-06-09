package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.*;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface IChartDataService {
    List<ChartDataXmlTotalAndValidateVo> xmlTotalAndValidate(Integer year);

    List<VehicleModelVo> vehicleModel(Integer year, Integer month);

    Map<String, Object> statisticsCard(ChartDataStatisticsDto statisticsDto);

    List<ChartDataXmlTotalVo> statisticsTrend(ChartDataStatisticsDto statisticsDto);

    Map<String, Object> statisticsXml(Long timestamp);

    List<String> selectAllVinsByDateRange(Date startTime, Date endTime);

    List<CalendarDayVo> getCalendarByMonth(String vin, int year, int month);

    List<AbnormalStatisticsVo> statisticsAbnormal(ChartDataStatisticsDto statisticsDto);

    CalendarDayDetailVo getCalendarOfDay(LocalDate date);

    Map<String, Map<String, Object>> timeoutStatistics();

    Map<String, Map<String, Object>> validateStatistics();
}
