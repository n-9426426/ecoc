package com.ruoyi.vehicle.task;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VehicleInfoUseOldTemplateTask {

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Scheduled(cron = "0 * * * * *")
    public void vehicleInfoUseOldTemplateJobHandler(){
        log.info("Scheduled:vehicleInfoUseOldTemplateJobHandler():分钟");
        List<VehicleInfo> vehicleInfoList = vehicleInfoMapper.checkOldTemplate();
        if (vehicleInfoList.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder("以下车辆信息:");
        msg.append(System.lineSeparator());
        for (VehicleInfo vehicleInfo : vehicleInfoList) {
            msg.append(vehicleInfo.getVin())
                    .append(System.lineSeparator());
        }
        msg.append("生成时使用的车辆模版不是最新版本的车辆模版");
        if(sentNotice(msg).getCode() == 200) {
            List<Long> vehicleInfoIds = vehicleInfoList.stream()
                    .map(VehicleInfo::getVehicleId)
                    .collect(Collectors.toList());
            if (vehicleInfoIds.isEmpty()){
                return;
            }
            vehicleInfoMapper.updateVehicleInfoOldTemplate(vehicleInfoIds, 1);
        }
    }

    private R<?> sentNotice(StringBuilder msg){
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆信息生成时使用非最新模版通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
