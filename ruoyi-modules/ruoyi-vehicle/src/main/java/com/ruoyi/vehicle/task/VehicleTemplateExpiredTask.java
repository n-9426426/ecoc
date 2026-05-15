package com.ruoyi.vehicle.task;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.utils.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class VehicleTemplateExpiredTask {
    private RemoteNoticeService remoteNoticeService;

    private VehicleTemplateMapper vehicleTemplateMapper;

    @Scheduled(cron = "0 0 * * * ?")
    public void vehicleTemplateExpiredJobHandler(){
        log.info("Scheduled:vehicleTemplateExpiredJobHandler():分钟");
        List<VehicleTemplate> vehicleTemplateList = vehicleTemplateMapper.selectExpiringTemplates();
        if (vehicleTemplateList.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        for (VehicleTemplate vehicleTemplate : vehicleTemplateList) {
            msg.append("WVTA-COC编号为: ")
                    .append(vehicleTemplate.getWvtaCocNo())
                    .append(", COC模板号为: ")
                    .append(vehicleTemplate.getCocTemplateNo())
                    .append(", 版本号为: ")
                    .append(vehicleTemplate.getVersion())
                    .append("的车辆模版还有")
                    .append(TimeUtils.getDateDiffDesc(new Date(), vehicleTemplate.getOverdueDate()))
                    .append("到期");
        }
        if(sentNotice(msg).getCode() == 200) {
            List<Long> vehicleTemplateIds = vehicleTemplateList.stream()
                    .map(VehicleTemplate::getTemplateId)
                    .collect(Collectors.toList());
            if (vehicleTemplateIds.isEmpty()){
                return;
            }
            vehicleTemplateMapper.updateVehicleTemplateExpired(vehicleTemplateIds, 1);
        }
    }

    private R<?> sentNotice(StringBuilder msg){
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆模版过期提醒");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(2, 3));
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
