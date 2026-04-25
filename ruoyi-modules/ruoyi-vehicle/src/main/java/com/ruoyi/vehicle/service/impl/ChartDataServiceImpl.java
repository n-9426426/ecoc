package com.ruoyi.vehicle.service.impl;

import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.vehicle.domain.VehicleLifecycle;
import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.*;
import com.ruoyi.vehicle.mapper.ChartDataMapper;
import com.ruoyi.vehicle.mapper.VehicleLifecycleMapper;
import com.ruoyi.vehicle.service.IChartDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChartDataServiceImpl implements IChartDataService {

    @Autowired
    private ChartDataMapper chartDataMapper;

    @Autowired
    private VehicleLifecycleMapper vehicleLifecycleMapper;

    @Autowired
    private RemoteDictService remoteDictService;

    @Override
    public List<ChartDataXmlTotalVo> xmlTotal(Integer year) {
        return chartDataMapper.selectXmlTotal(year);
    }

    @Override
    public List<ChartDataXmlTotalVo> xmlValidate(Integer year) {
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
    public List<ChartDataXmlTotalVo> statisticsTrend(ChartDataStatisticsDto statisticsDto) {
        initChartDataStatisticsDtoDate(statisticsDto);
        return chartDataMapper.selectStatisticsTrend(statisticsDto);
    }

    @Override
    public Map<String, Object> statisticsXml(Integer year) {
        LocalDateTime start = LocalDateTime.of(year, 1, 1, 0, 0, 0);
        LocalDateTime end   = LocalDateTime.of(year, 12, 31, 23, 59, 59, 999_999_999);
        ChartDataStatisticsDto chartDataStatisticsDto = new ChartDataStatisticsDto();
        chartDataStatisticsDto.setStartTime(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()));
        chartDataStatisticsDto.setEndTime(Date.from(end.atZone(ZoneId.systemDefault()).toInstant()));
        Map<String, Object> result = new HashMap<>();
        result = statisticsCard(chartDataStatisticsDto);
        result.put("vehicleWaitNumber", chartDataMapper.selectStatisticsVehicleWaitNumber(year));
        return result;
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

    @Override
    public List<VinCalendarVo> getAllVinCalendarByMonth(int year, int month) {
        // 1. 查询所有 vin
        List<String> allVins = vehicleLifecycleMapper.selectAllVins();

        if (allVins == null || allVins.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 遍历每个 vin，复用原有的 getCalendarByMonth 逻辑
        List<VinCalendarVo> result = new ArrayList<>();
        for (String vin : allVins) {
            List<CalendarDayVo> calendar = getCalendarByMonth(vin, year, month);
            result.add(VinCalendarVo.builder()
                    .vin(vin)
                    .calendar(calendar)
                    .build());
        }
        return result;
    }

    @Override
    public List<CalendarDayVo> getCalendarByMonth(String vin, int year, int month) {
        int total_stages = remoteDictService.getDictDataByType("vehicle_lifecycle").getData().size();

        // 1. 计算当月的起止时间
        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate lastDay  = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

        LocalDateTime monthStart = firstDay.atStartOfDay();
        LocalDateTime monthEnd   = lastDay.atTime(23, 59, 59, 999_999_999);

        // 2. 查询当月所有记录（用于圆点逻辑）
        List<VehicleLifecycle> monthRecords = vehicleLifecycleMapper.selectByVinAndDateRange(vin, monthStart, monthEnd);

        // 按日期分组：key=日期字符串 yyyy-MM-dd，value=当天记录列表
        Map<String, List<VehicleLifecycle>> recordsByDay = monthRecords.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getTime()
                        .toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()));

        // 3. 逐天构建 CalendarDayVO
        List<CalendarDayVo> result = new ArrayList<>();
        LocalDate cursor = firstDay;

        while (!cursor.isAfter(lastDay)) {

            String dateStr = cursor.toString();
            LocalDateTime dayEnd = cursor.atTime(23, 59, 59, 999_999_999);

            // ---- 阶段显示逻辑 ----
            // 查询截至当天结束时刻，每个 operate 的最新一条记录
            List<VehicleLifecycle> latestPerOperate =
                    vehicleLifecycleMapper.selectLatestPerOperateBeforeTime(vin, dayEnd);

            // 构建 Map<operate, LocalDateTime>
            Map<Integer, Date> operateTimeMap = new HashMap<>();
            for (VehicleLifecycle record : latestPerOperate) {
                int op = Integer.parseInt(record.getOperate());
                operateTimeMap.put(op, record.getTime());
            }

            // 确定当前最大已完成阶段（取 operate 最大值）
            int currentOperate = operateTimeMap.keySet().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(-1); // -1 表示无任何记录

            List<CalendarDayVo.StageStatus> stages = new ArrayList<>();

            for (int i = 0; i < total_stages; i++) {
                String status;
                Date operateTime = null;

                if (currentOperate == -1) {
                    // 该天之前从未有任何操作，全置灰
                    status = "grey";
                } else if (i <= currentOperate) {
                    status = "active";
                    // 取该阶段操作时间（可能是历史某天触发的，不一定是当天）
                    operateTime = operateTimeMap.get(i);
                } else {
                    status = "grey";
                }

                stages.add(CalendarDayVo.StageStatus.builder()
                        .operate(i)
                        .status(status)
                        .time(operateTime)
                        // todo 结果
                        .result(0)
                        .build());
            }

            // ---- 圆点逻辑 ----
            List<VehicleLifecycle> todayRecords = recordsByDay.get(dateStr);
            String dotColor;

            if (todayRecords == null || todayRecords.isEmpty()) {
                dotColor = "none";
            } else {
                boolean hasFailed = todayRecords.stream()
                        .anyMatch(r -> r.getResult() != null && r.getResult() == 1);
                dotColor = hasFailed ? "red" : "green";
            }

            result.add(CalendarDayVo.builder()
                    .date(dateStr)
                    .stages(stages)
                    .dotColor(dotColor)
                    .build());

            cursor = cursor.plusDays(1);
        }

        return result;
    }

    @Override
    public  List<AbnormalStatisticsVo> statisticsAbnormal(ChartDataStatisticsDto statisticsDto) {
        initChartDataStatisticsDtoDate(statisticsDto);
        return chartDataMapper.selectStatisticsAbnormal(statisticsDto);
    }
}
