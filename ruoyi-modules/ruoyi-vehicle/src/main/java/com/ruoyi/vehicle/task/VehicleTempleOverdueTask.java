package com.ruoyi.vehicle.task;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
@Component
public class VehicleTempleOverdueTask {

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

/*    @Scheduled(cron = "0 * * * * *")
    public void vehicleTemplateOverdueJobHandler(){
        vehicleTemplateMapper.updateStatusByOverdueDate();
    }*/

    @Scheduled(cron = "0 * * * * ?")
    public void vehicleTemplateOverdueButNoNextVersionJobHandler() {
        List<VehicleTemplate> vehicleTemplateList = vehicleTemplateMapper.selectVehicleTemplateOverdueButNoNextVersion();
        if (vehicleTemplateList.isEmpty()) {
            return;
        }
        for (VehicleTemplate vehicleTemplate : vehicleTemplateList) {
            vehicleTemplateMapper.updateVehicleTemplateNoNextVersion(Collections.singletonList(vehicleTemplate.getTemplateId()), 1);
            StringBuilder msg = new StringBuilder();
            msg.append("TVV ")
                    .append(vehicleTemplate.getTvv().replace(",", ""))
                    .append(" (版本")
                    .append(vehicleTemplate.getVersion())
                    .append(")")
                    .append(" 的车辆模版将于 ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(vehicleTemplate.getOverdueDate()))
                    .append(" 到期, 到期后该模版将没有更新版本使用");
            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(vehicleTemplate.getTemplateId()));
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
        sysNotice.setNoticeTitle("车辆模版过期后没有更新版本通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(4, 5));
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
