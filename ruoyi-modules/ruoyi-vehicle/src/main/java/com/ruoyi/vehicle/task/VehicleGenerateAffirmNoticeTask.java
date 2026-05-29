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
import java.util.stream.Collectors;

@Slf4j
@Component
public class VehicleGenerateAffirmNoticeTask {

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Scheduled(cron = "0 * * * * *")
    public void vehicleGenerateAffirmNoticeJobHandler() {
        log.info("Scheduled:vehicleGenerateAffirmNoticeJobHandler():分钟");
        List<VehicleInfo> vehicleInfoList = vehicleInfoMapper.listPendingGenerateAffirmNotice();
        if (vehicleInfoList.isEmpty()) {
            return;
        }
        for (VehicleInfo vehicleInfo : vehicleInfoList) {
            StringBuilder msg = new StringBuilder();
            msg.append("物料号 ").append(vehicleInfo.getMaterialNo())
                    .append(" 生成待确认");
            Map<String, String> params = new HashMap<>();
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            params.put("materialNo", vehicleInfo.getMaterialNo());
            sentNotice(msg, params);
        }
        List<Long> vehicleIds = vehicleInfoList.stream()
                .map(VehicleInfo::getVehicleId)
                .collect(Collectors.toList());
        vehicleInfoMapper.updateGenerateAffirmNotice(vehicleIds, 1);
    }

    private R<?> sentNotice(StringBuilder msg, Map<String, String> params) {
        SysNotice sysNotice = new SysNotice();
        sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
        sysNotice.setQueryParams(JSON.toJSONString(params));
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("首台车生成待确认通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(18, 19));
        return remoteNoticeService.innerAdd(sysNotice);
    }
}