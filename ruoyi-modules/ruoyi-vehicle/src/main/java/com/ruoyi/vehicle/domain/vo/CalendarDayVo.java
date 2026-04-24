package com.ruoyi.vehicle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarDayVo {
    /** 日期，格式 yyyy-MM-dd */
    private String date;

    /**
     * status: "active"=正常显示, "grey"=置灰, "none"=无任何操作（整天无记录）
     */
    private List<StageStatus> stages;

    /**
     * 圆点颜色: "red"=有失败, "green"=全成功, "none"=无操作
     */
    private String dotColor;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageStatus {
        /** 阶段编号 0~4 */
        private Integer operate;
        /** "active" / "grey" */
        private String status;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date time;

        private Integer result;
    }
}
