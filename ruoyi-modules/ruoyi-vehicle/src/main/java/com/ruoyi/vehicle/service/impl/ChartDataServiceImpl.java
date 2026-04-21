package com.ruoyi.vehicle.service.impl;

import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;
import com.ruoyi.vehicle.domain.vo.VehicleModelVo;
import com.ruoyi.vehicle.mapper.ChartDataMapper;
import com.ruoyi.vehicle.service.IChartDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChartDataServiceImpl implements IChartDataService {

    @Autowired
    private ChartDataMapper chartDataMapper;


    @Override
    public ChartDataXmlTotalVo xmlTotal(Integer year) {
        return chartDataMapper.selectXmlTotal(year);
    }

    @Override
    public ChartDataXmlTotalVo xmlValidate(Integer year) {
        return chartDataMapper.selectXmlValidateTotal(year);
    }

    @Override
    public List<VehicleModelVo> vehicleModel(Integer year, Integer month) {
        return chartDataMapper.selectVehicleModelVoList(year, month);
    }

    @Override
    public Map<String, Object> statisticsCard(ChartDataStatisticsDto statisticsDto) {
        initChartDataStatisticsDtoDate(statisticsDto);
        Map<String, Object> result = new HashMap<>();
        result.put("xmlTotal", chartDataMapper.selectStatisticsXmlTotal(statisticsDto));
        result.put("xmlPassNumber", chartDataMapper.selectStatisticsXmlPassNumber(statisticsDto));
        result.put("vehicleFailNumber", chartDataMapper.selectStatisticsVehicleFailNumber(statisticsDto));
        result.put("xmlRejectNumber", chartDataMapper.selectStatisticsXmlRejectNumber(statisticsDto));
        return result;
    }

    @Override
    public ChartDataXmlTotalVo statisticsTrend(ChartDataStatisticsDto statisticsDto) {
        initChartDataStatisticsDtoDate(statisticsDto);
        return chartDataMapper.selectStatisticsTrend(statisticsDto);
    }

    private void initChartDataStatisticsDtoDate(ChartDataStatisticsDto statisticsDto) {
        LocalDateTime startOfYear = LocalDateTime.of(LocalDate.now().getYear(), 1, 1, 0, 0, 0);
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);
        if (statisticsDto.getStartTime() == null) {
            statisticsDto.setStartTime(Date.from(startOfYear.atZone(ZoneId.systemDefault()).toInstant()));
        }
        if (statisticsDto.getEndTime() == null) {
            statisticsDto.setEndTime(Date.from(startOfNextYear.atZone(ZoneId.systemDefault()).toInstant()));
        }
    }
}
