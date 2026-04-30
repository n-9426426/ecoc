package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.vehicle.domain.VehicleLifecycle;
import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.domain.vo.AbnormalStatisticsVo;
import com.ruoyi.vehicle.domain.vo.CalendarDayVo;
import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;
import com.ruoyi.vehicle.domain.vo.VehicleModelVo;
import com.ruoyi.vehicle.mapper.ChartDataMapper;
import com.ruoyi.vehicle.mapper.VehicleLifecycleMapper;
import com.ruoyi.vehicle.service.IChartDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

        // 当前周期数据
        long xmlTotal = chartDataMapper.selectStatisticsXmlTotal(statisticsDto);
        long xmlPassNumber = chartDataMapper.selectStatisticsXmlPassNumber(statisticsDto);
        long vehicleFailNumber = chartDataMapper.selectStatisticsVehicleFailNumber(statisticsDto);
        long xmlRejectNumber = chartDataMapper.selectStatisticsXmlRejectNumber(statisticsDto);

        result.put("xmlTotal", xmlTotal);
        result.put("xmlPassNumber", xmlPassNumber);
        result.put("vehicleFailNumber", vehicleFailNumber);
        result.put("xmlRejectNumber", xmlRejectNumber);

        // 计算上个周期并添加环比数据
        String flag = statisticsDto.getFlag();
        if (flag != null && (flag.equals("day") || flag.equals("month") || flag.equals("year"))) {
            ChartDataStatisticsDto prevDto = buildPreviousPeriodDto(statisticsDto);
            if (prevDto != null) {
                long prevXmlTotal = chartDataMapper.selectStatisticsXmlTotal(prevDto);
                long prevXmlPassNumber = chartDataMapper.selectStatisticsXmlPassNumber(prevDto);
                long prevVehicleFailNumber = chartDataMapper.selectStatisticsVehicleFailNumber(prevDto);
                long prevXmlRejectNumber = chartDataMapper.selectStatisticsXmlRejectNumber(prevDto);

                addGrowthRate(result, "xmlTotalGrowthRate", xmlTotal, prevXmlTotal);
                addGrowthRate(result, "xmlPassNumberGrowthRate", xmlPassNumber, prevXmlPassNumber);
                addGrowthRate(result, "vehicleFailNumberGrowthRate", vehicleFailNumber, prevVehicleFailNumber);
                addGrowthRate(result, "xmlRejectNumberGrowthRate", xmlRejectNumber, prevXmlRejectNumber);
            }
        }

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
        result.put("vehicleWaitNumber", chartDataMapper.selectStatisticsVehicleWaitNumber(start, end));
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
    public List<String> selectAllVinsByDateRange(Date startTime, Date endTime) {
        return vehicleLifecycleMapper.selectAllVinsByDateRange(startTime, endTime);
    }

    @Override
    public List<CalendarDayVo> getCalendarByMonth(String vin, int year, int month) {
        int totalStages = remoteDictService.getDictDataByType("vehicle_lifecycle").getData().size();

        if (StringUtils.isBlank(vin)) {
            return Collections.emptyList();
        }

        // 1. 计算当月起止时间
        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate lastDay  = firstDay.withDayOfMonth(firstDay.lengthOfMonth());
        LocalDateTime monthStart = firstDay.atStartOfDay();
        LocalDateTime monthEnd   = lastDay.atTime(23, 59, 59, 999_999_999);

        // 2. 查询当月所有记录（用于圆点逻辑）
        List<VehicleLifecycle> monthRecords = vehicleLifecycleMapper.selectByVinAndDateRange(vin, monthStart, monthEnd);

        // 按日期分组：key=yyyy-MM-dd
        Map<String, List<VehicleLifecycle>> recordsByDay = monthRecords.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getTime()
                                .toInstant()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .toString()));

        // 3. 逐天构建 CalendarDayVo
        List<CalendarDayVo> result = new ArrayList<>();

        for (LocalDate cursor = firstDay; !cursor.isAfter(lastDay); cursor = cursor.plusDays(1)) {
            String dateStr = cursor.toString();
            LocalDateTime dayEnd = cursor.atTime(23, 59, 59, 999_999_999);

            // ---- 阶段显示逻辑 ----
            // 查询截至当天结束时刻，每个 operate 的最新一条记录
            List<VehicleLifecycle> latestPerOperate = vehicleLifecycleMapper.selectLatestPerOperateBeforeTime(vin, dayEnd);

            // 构建 Map<operate, VehicleLifecycle>，保留完整记录（time + result）
            Map<Integer, VehicleLifecycle> operateRecordMap = latestPerOperate.stream()
                    .collect(Collectors.toMap(
                            r -> Integer.parseInt(r.getOperate()),
                            r -> r,
                            (existing, replacement) -> existing // 理论上已去重，保留先者
                    ));
            // 确定当前最大已完成阶段
            int currentOperate = operateRecordMap.keySet().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(-1);

            // 构建各阶段状态
            List<CalendarDayVo.StageStatus> stages = new ArrayList<>(totalStages);
            for (int i = 0; i < totalStages; i++) {
                String status;
                Date operateTime = null;
                Integer stageResult = null;

                if (currentOperate != -1 && i <= currentOperate) {
                    status = "active";
                    VehicleLifecycle record = operateRecordMap.get(i);
                    if (record != null) {
                        operateTime = record.getTime();
                        stageResult = record.getResult();
                    }
                } else {
                    status = "grey";
                }

                stages.add(CalendarDayVo.StageStatus.builder()
                        .operate(i)
                        .status(status)
                        .time(operateTime)
                        .result(stageResult)
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
        }

        return result;
    }

    @Override
    public  List<AbnormalStatisticsVo> statisticsAbnormal(ChartDataStatisticsDto statisticsDto) {
        initChartDataStatisticsDtoDate(statisticsDto);
        return chartDataMapper.selectStatisticsAbnormal(statisticsDto);
    }

    /**
     * 构建上个周期的查询 DTO
     */
    private ChartDataStatisticsDto buildPreviousPeriodDto(ChartDataStatisticsDto current) {
        Date startTime = current.getStartTime();
        Date endTime = current.getEndTime();
        if (startTime == null || endTime == null) {
            return null;
        }

        String flag = current.getFlag();
        Calendar startCal = Calendar.getInstance();
        Calendar endCal = Calendar.getInstance();
        startCal.setTime(startTime);
        endCal.setTime(endTime);

        switch (flag) {
            case "day":
                startCal.add(Calendar.DAY_OF_MONTH, -1);
                endCal.add(Calendar.DAY_OF_MONTH, -1);
                break;
            case "month":
                shiftByMonth(startCal, -1);
                shiftByMonth(endCal, -1);
                break;
            case "year":
                shiftByYear(startCal, -1);
                shiftByYear(endCal, -1);
                break;
            default:
                return null;
        }

        ChartDataStatisticsDto prevDto = new ChartDataStatisticsDto();
        // 复制非时间字段
        prevDto.setFactoryCode(current.getFactoryCode());
        prevDto.setVehicleModel(current.getVehicleModel());
        prevDto.setCountry(current.getCountry());
        prevDto.setUploadResult(current.getUploadResult());
        prevDto.setAbnormalType(current.getAbnormalType());
        prevDto.setFlag(current.getFlag());
        // 设置上个周期时间
        prevDto.setStartTime(startCal.getTime());
        prevDto.setEndTime(endCal.getTime());
        return prevDto;
    }

    /**
     * 按月偏移，处理月末边界问题
     * 例如：3月31日 -> 2月28日（非3月3日）
     */
    private void shiftByMonth(Calendar cal, int months) {
        int originalDay = cal.get(Calendar.DAY_OF_MONTH);
        cal.add(Calendar.MONTH, months);
        // 获取目标月份的最大天数
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        // 若原始日期超过目标月份最大天数，则设置为该月最后一天
        if (originalDay > maxDay) {
            cal.set(Calendar.DAY_OF_MONTH, maxDay);
        }
    }

    /**
     * 按年偏移，处理闰年边界问题
     * 例如：2024年2月29日 -> 2023年2月28日
     */
    private void shiftByYear(Calendar cal, int years) {
        int originalDay = cal.get(Calendar.DAY_OF_MONTH);
        cal.add(Calendar.YEAR, years);
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (originalDay > maxDay) {
            cal.set(Calendar.DAY_OF_MONTH, maxDay);
        }
    }

    /**
     * 计算环比增长率并放入结果 Map
     * 上个周期为 0 时不添加该字段
     *
     * @param result      结果 Map
     * @param key         字段名
     * @param current     当前周期值
     * @param previous    上个周期值
     */
    private void addGrowthRate(Map<String, Object> result, String key, long current, long previous) {
        if (previous == 0) {
            // 上周期为 0，不添加环比数据
            return;
        }
        // 保留两位小数，例如：12.34 表示增长了 12.34%
        BigDecimal rate = BigDecimal.valueOf(current - previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(previous), 2, RoundingMode.HALF_UP);
        result.put(key, rate);
    }
}
