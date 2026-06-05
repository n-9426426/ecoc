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
public class CalendarDayDetailVo {
    /** 日期，格式 yyyy-MM-dd */
    private String date;
    /** 当天出现过的操作阶段列表 */
    private List<OperateItem> operates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperateItem {
        /** operate 编码（字典 key） */
        private String operate;
        /** operate 中文名称（从字典取） */
        private String operateName;

        private Integer result;
    }
}
