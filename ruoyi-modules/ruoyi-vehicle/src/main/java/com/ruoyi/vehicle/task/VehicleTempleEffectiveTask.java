package com.ruoyi.vehicle.task;

import com.ruoyi.vehicle.mapper.MaterialHistoryMapper;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VehicleTempleEffectiveTask {

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private MaterialHistoryMapper materialHistoryMapper;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Scheduled(cron = "0 * * * * *")
    public void vehicleTemplateEffectiveJobHandler(){
        log.info("Scheduled:vehicleTemplateEffectiveJobHandler():分钟");
        // todo 当模版启用的时候，需要检查物料号管理的自动更新版本是否启用，如果启用，需要同步更改物料号管理的的newVersion
        vehicleTemplateMapper.updateStatusByEffectiveDate();
    }
}
