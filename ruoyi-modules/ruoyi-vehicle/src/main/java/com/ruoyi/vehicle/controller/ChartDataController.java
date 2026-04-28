package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.vehicle.domain.dto.ChartDataStatisticsDto;
import com.ruoyi.vehicle.service.IChartDataService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@RestController
@RequestMapping("/chart/data")
public class ChartDataController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ChartDataController.class);

    @Autowired
    private IChartDataService chartDataService;

    @Operation(summary = "XML汇总")
    @GetMapping("/xml/total/{year}")
    public AjaxResult xmlTotal(@PathVariable Integer year) {
        return AjaxResult.success(chartDataService.xmlTotal(year));
    }

    @Operation(summary = "XML文件校验")
    @GetMapping("/xml/validate/{year}")
    public AjaxResult xmlValidate(@PathVariable Integer year) {
        return AjaxResult.success(chartDataService.xmlValidate(year));
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
    @PostMapping("/statistics/xml/{year}")
    public AjaxResult statisticsXml(@PathVariable Integer year) {
        return AjaxResult.success(chartDataService.statisticsXml(year));
    }

    @GetMapping("/vin")
    public AjaxResult selectAllVinsByDateRange(@RequestParam Long startTime,
                                               @RequestParam Long  endTime) {
        Date startDate = new Date(startTime);
        Date endDate = new Date(endTime);
        return AjaxResult.success(chartDataService.selectAllVinsByDateRange(startDate, endDate));
    }

    /**
     * 获取某 vin 某月的日历数据
     * GET /vehicle/lifecycle/calendar?vin=xxx&year=2024&month=6
     */
    @GetMapping("/calendar")
    public AjaxResult getCalendar(@RequestParam String vin, @RequestParam int year, @RequestParam int month) {
        return AjaxResult.success(chartDataService.getCalendarByMonth(vin, year, month));
    }
}
