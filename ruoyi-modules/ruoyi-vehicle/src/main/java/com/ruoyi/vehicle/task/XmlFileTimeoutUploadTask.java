package com.ruoyi.vehicle.task;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.XmlFile;
import com.ruoyi.vehicle.mapper.XmlFileMapper;
import com.ruoyi.vehicle.utils.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Component
public class XmlFileTimeoutUploadTask {

    @Autowired
    private XmlFileMapper xmlFileMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Scheduled(cron = "0 * * * * ?")
    public void xmlFileTimeoutUploadJobHandler(){
        List<XmlFile> xmlFileList = xmlFileMapper.checkXmlFileTimeoutUpload();
        if (xmlFileList.isEmpty()) {
            return;
        }
        for (XmlFile xmlFile : xmlFileList) {
            xmlFileMapper.updateXmlFileTimeoutUpload(Collections.singletonList(xmlFile.getId()), 1);
            StringBuilder msg = new StringBuilder();
            StringBuilder overdueTime = new StringBuilder();
            Object[][] parts = TimeUtils.getDateDiffParts(xmlFile.getCreateTime(), xmlFile.getUpdateTime());
            for (Object[] part : parts) {
                long value = (long) part[0];
                ChronoUnit unit = (ChronoUnit) part[1];
                overdueTime.append(value).append(unit.name());
            }
            msg.append("VIN ")
                    .append(xmlFile.getVin())
                    .append(" 于")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(xmlFile.getCreateTime()))
                    .append("生成XML文件，已超时")
                    .append(overdueTime)
                    .append("未上传");
            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(xmlFile.getId()));
            params.put("vin", xmlFile.getVin());
            params.put("modelCode", xmlFile.getModelCode());
            params.put("factoryCode", xmlFile.getFactoryCode());
            params.put("country", xmlFile.getCountry());
            params.put("issueDate", com.ruoyi.common.core.utils.DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", xmlFile.getIssueDate()));
            sentNotice(msg, params);
        }
    }

    private R<?> sentNotice(StringBuilder msg, Map<String, String> params){
        SysNotice sysNotice = new SysNotice();
        sysNotice.setModel(SysNoticeModel.XML_FILE.getModel());
        sysNotice.setQueryParams(JSON.toJSONString(params));
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("XML文件超时未上传通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(6, 7));
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
