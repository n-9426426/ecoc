package com.ruoyi.vehicle.task;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VehicleTempleOverdueTask {

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Scheduled(cron = "0 * * * * *")
    public void vehicleTemplateOverdueJobHandler(){
        log.info("Scheduled:vehicleTemplateOverdueJobHandler():分钟");
        vehicleTemplateMapper.updateStatusByOverdueDate();
    }

    @Scheduled(cron = "0 0 * * * ?")
    public void vehicleTemplateOverdueButNoNextVersionJobHandler() {
        log.info("Scheduled:vehicleTemplateOverdueButNoNextVersionJobHandler():小时");
        List<VehicleTemplate> vehicleTemplateList = vehicleTemplateMapper.selectVehicleTemplateOverdueButNoNextVersion();
        if (vehicleTemplateList.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        for (VehicleTemplate vehicleTemplate : vehicleTemplateList) {
            msg.append("TVV为 ")
                    .append(vehicleTemplate.getTvv().replace(",", ""))
                    .append("(版本")
                    .append(vehicleTemplate.getVersion())
                    .append(")")
                    .append(" 的车辆模版将于")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(vehicleTemplate.getOverdueDate()))
                    .append(System.lineSeparator());
        }
        msg.append("生成时使用的车辆模版不是最新版本的车辆模版");
        if(sentNotice(msg).getCode() == 200) {
            List<Long> vehicleTemplateIds = vehicleTemplateList.stream()
                    .map(VehicleTemplate::getTemplateId)
                    .collect(Collectors.toList());
            if (vehicleTemplateIds.isEmpty()) {
                return;
            }
            vehicleTemplateMapper.updateVehicleTemplateNoNextVersion(vehicleTemplateIds, 1);
        }
    }

    private R<?> sentNotice(StringBuilder msg){
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆模版过期后没有更新版本通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
