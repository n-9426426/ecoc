package com.ruoyi.vehicle.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VinCalendarVo {
    /** 车辆 VIN */
    private String vin;
    /** 该 vin 当月每天的日历数据 */
    private List<CalendarDayVo> calendar;
}
