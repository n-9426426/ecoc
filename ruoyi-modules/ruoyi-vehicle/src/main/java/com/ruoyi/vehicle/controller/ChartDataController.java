package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.service.IChartDataService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Date;

@RestController
@RequestMapping("/chart/data")
public class ChartDataController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ChartDataController.class);

    @Autowired
    private IChartDataService chartDataService;

    @Operation(summary = "XML汇总与校验合并")
    @GetMapping("/xml/total-and-validate/{year}")
    public AjaxResult xmlTotalAndValidate(@PathVariable Integer year) {
        return AjaxResult.success(chartDataService.xmlTotalAndValidate(year));
    }

    @Operation(summary = "车型分布")
    @GetMapping("/vehicle/model/{year}")
    public AjaxResult vehicleModel(@PathVariable Integer year, @RequestParam(required = false) Integer month) {
        return AjaxResult.success(chartDataService.vehicleModel(year, month));
    }

    @Operation(summary = "报表统计面板：4个卡片")
    @PostMapping("/statistics/card")
    public AjaxResult statisticsCard(@RequestBody ChartDataStatisticsDto statisticsDto) {
        return AjaxResult.success(chartDataService.statisticsCard(statisticsDto));
    }

    @Operation(summary = "报表统计面板：XML 生成与提交流水线趋势")
    @PostMapping("/statistics/trend")
    public AjaxResult statisticsTrend(@RequestBody ChartDataStatisticsDto statisticsDto) {
        return AjaxResult.success(chartDataService.statisticsTrend(statisticsDto));
    }

    @Operation(summary = "报表统计面板:异常原因占比分析")
    @PostMapping("/statistics/abnormal")
    public AjaxResult statisticsAbnormal(@RequestBody ChartDataStatisticsDto statisticsDto) {
        return AjaxResult.success(chartDataService.statisticsAbnormal(statisticsDto));
    }

    @Operation(summary = "XML统计")
    @PostMapping({"/statistics/xml/{timestamp}", "/statistics/xml"})
    public AjaxResult statisticsXml(@PathVariable(required = false) Long timestamp) {
        timestamp = (timestamp != null) ? timestamp : System.currentTimeMillis();
        return AjaxResult.success(chartDataService.statisticsXml(timestamp));
    }

    @Operation(summary = "首页-重点提醒统计-超时统计")
    @GetMapping("/timeout")
    public AjaxResult timeoutStatistics() {
        return AjaxResult.success(chartDataService.timeoutStatistics());
    }

    @Operation(summary = "首页-重点提醒统计-校验统计")
    @GetMapping("/validate")
    public AjaxResult validateStatistics() {
        return AjaxResult.success(chartDataService.validateStatistics());
    }

    @GetMapping("/vin")
    @Operation(summary = "获取当天vin")
    public AjaxResult selectAllVinsByDateRange(@RequestParam Long startTime,
                                               @RequestParam Long  endTime) {
        Date startDate = new Date(startTime);
        Date endDate = new Date(endTime);
        return AjaxResult.success(chartDataService.selectAllVinsByDateRange(startDate, endDate));
    }

    @GetMapping("/calendar")
    @Operation(summary = "根据vin获取当天操作历史")
    public AjaxResult getCalendar(@RequestParam String vin, @RequestParam int year, @RequestParam int month) {
        return AjaxResult.success(chartDataService.getCalendarByMonth(vin, year, month));
    }

    @GetMapping("/calendar/day")
    @Operation(summary = "悬浮到带红点的日期会提示有哪些问题")
    public AjaxResult getCalendarOfDay(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return AjaxResult.success(chartDataService.getCalendarOfDay(date));
    }
}
