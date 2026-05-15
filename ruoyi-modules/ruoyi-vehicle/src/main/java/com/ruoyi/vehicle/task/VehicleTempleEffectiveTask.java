package com.ruoyi.vehicle.task;

import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VehicleTempleEffectiveTask {

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Scheduled(cron = "0 * * * * *")
    public void vehicleTemplateEffectiveJobHandler(){
        log.info("Scheduled:vehicleTemplateEffectiveJobHandler():分钟");
        vehicleTemplateMapper.updateStatusByEffectiveDate();
    }
}
