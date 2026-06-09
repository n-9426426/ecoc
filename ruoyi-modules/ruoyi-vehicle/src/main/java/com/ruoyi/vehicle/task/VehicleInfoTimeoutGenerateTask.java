package com.ruoyi.vehicle.task;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class VehicleInfoTimeoutGenerateTask {

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Scheduled(cron = "0 * * * * *")
    public void vehicleInfoUseOldTemplateJobHandler(){
        Integer noticeStatus = 0;
        List<VehicleInfo> vehicleInfoList = vehicleInfoMapper.checkVehicleInfoTimeoutGenerate(noticeStatus);
        if (vehicleInfoList.isEmpty()) {
            return;
        }
        for (VehicleInfo vehicleInfo : vehicleInfoList) {
            vehicleInfoMapper.updateVehicleInfoGenerateTimeout(Collections.singletonList(vehicleInfo.getVehicleId()), 1);
            StringBuilder msg = new StringBuilder();
            msg.append("车辆vin ").append(vehicleInfo.getVin())
                    .append(" 超时未生成，该信息创建时间为 ")
                    .append(com.alibaba.fastjson2.util.DateUtils.format(vehicleInfo.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            if (vehicleInfo.getIssueDate() != null) {
                params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            }
            params.put("materialNo", vehicleInfo.getMaterialNo());
            sentNotice(msg, params);
        }
    }

    private R<?> sentNotice(StringBuilder msg, Map<String, String> params) {
        SysNotice sysNotice = new SysNotice();
        sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
        sysNotice.setQueryParams(JSON.toJSONString(params));
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆信息超时未生成XML文件通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(22, 23));
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
