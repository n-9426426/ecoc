package com.ruoyi.vehicle.task;

import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

@Component
@Slf4j
public class VehicleTemplateExpiredTask {
    private RemoteNoticeService remoteNoticeService;

    private VehicleTemplateMapper vehicleTemplateMapper;

    @Scheduled(cron = "0 0 8 * * ?")
    public void vehicleTemplateExpiredJobHandler(){
        List<VehicleTemplate> vehicleTemplates = vehicleTemplateMapper.selectExpiringTemplates();
        SysNotice sysNotice = null;
        for (VehicleTemplate vehicleTemplate : vehicleTemplates) {
            sysNotice = new SysNotice();
            sysNotice.setNoticeTitle("车辆模版过期提醒");
            sysNotice.setNoticeType("1");
            sysNotice.setStatus("0");
            sysNotice.setCreateBy("自动提醒");
            sysNotice.setCreateTime(new Date());
            String content = "WVTA-COC编号为：" + vehicleTemplate.getWvtaCocNo()
                    +"，COC模板号为：" + vehicleTemplate.getCocTemplateNo()
                    + "，版本号为：" + vehicleTemplate.getVersion()
                    + "的车辆模版还有" + getOverdueDiffDesc(vehicleTemplate.getOverdueDate()) + "到期";
            remoteNoticeService.add(sysNotice);
        }
    }

    /**
     * 获取当前时间与过期时间的差值描述
     * - 同一小时内：返回分钟数
     * - 同一天内：返回小时数
     * - 同一年内：返回天数
     */
    public static String getOverdueDiffDesc(Date overdueDate) {
        if (overdueDate == null) {
            return "";
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime overdue = overdueDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        // 同一小时：精确到分钟
        if (now.getYear() == overdue.getYear()
                && now.getDayOfYear() == overdue.getDayOfYear()
                && now.getHour() == overdue.getHour()) {
            long minutes = ChronoUnit.MINUTES.between(now, overdue);
            return minutes + "分钟";
        }

        // 同一天：精确到小时
        if (now.getYear() == overdue.getYear()
                && now.getDayOfYear() == overdue.getDayOfYear()) {
            long hours = ChronoUnit.HOURS.between(now, overdue);
            return hours + "小时";
        }

        // 同一年：精确到天
        if (now.getYear() == overdue.getYear()) {
            long days = ChronoUnit.DAYS.between(now.toLocalDate(), overdue.toLocalDate());
            return days + "天";
        }

        // 跨年兜底
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), overdue.toLocalDate());
        return days + "天";
    }
}
