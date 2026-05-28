package com.ruoyi.vehicle.task;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.utils.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class VehicleTemplateExpiredTask {

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Scheduled(cron = "0 * * * * ?")
    public void vehicleTemplateExpiredJobHandler(){
        log.info("Scheduled:vehicleTemplateExpiredJobHandler():分钟");
        List<VehicleTemplate> vehicleTemplateList = vehicleTemplateMapper.selectExpiringTemplates(0);
        if (vehicleTemplateList.isEmpty()) {
            return;
        }
        for (VehicleTemplate vehicleTemplate : vehicleTemplateList) {
            vehicleTemplateMapper.updateVehicleTemplateExpired(Collections.singletonList(vehicleTemplate.getTemplateId()), 1);
            StringBuilder msg = new StringBuilder();
            msg.append("WVTA-COC编号 ")
                    .append(vehicleTemplate.getWvtaCocNo())
                    .append(" 、COC模板号为 ")
                    .append(vehicleTemplate.getCocTemplateNo())
                    .append(" 、版本号为 ")
                    .append(vehicleTemplate.getVersion())
                    .append(" 的车辆模版还有")
                    .append(TimeUtils.getDateDiffDesc(new Date(), vehicleTemplate.getOverdueDate()))
                    .append("到期");
            Map<String, String> params = new HashMap<>();
            params.put("wvtaCocNo", vehicleTemplate.getWvtaCocNo());
            params.put("cocTemplateNo", vehicleTemplate.getCocTemplateNo());
            params.put("modelNo", vehicleTemplate.getModelNo());
            params.put("vehicleType", vehicleTemplate.getVehicleType());
            sentNotice(msg, params);
        }
    }

    private R<?> sentNotice(StringBuilder msg, Map<String, String> params){
        SysNotice sysNotice = new SysNotice();
        sysNotice.setModel(SysNoticeModel.VEHICLE_TEMPLATE.getModel());
        sysNotice.setQueryParams(JSON.toJSONString(params));
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆模版临期提醒");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(2, 3));
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
