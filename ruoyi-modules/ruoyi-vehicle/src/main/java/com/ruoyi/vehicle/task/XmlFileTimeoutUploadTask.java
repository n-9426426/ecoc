package com.ruoyi.vehicle.task;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.vehicle.domain.XmlFile;
import com.ruoyi.vehicle.mapper.XmlFileMapper;
import com.ruoyi.vehicle.utils.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class XmlFileTimeoutUploadTask {

    @Autowired
    private XmlFileMapper xmlFileMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Scheduled(cron = "0 0 * * * ?")
    public void xmlFileTimeoutUploadJobHandler(){
        log.info("Scheduled:xmlFileTimeoutUploadJobHandler():小时");
        List<XmlFile> xmlFileList = xmlFileMapper.checkXmlFileTimeoutUpload();
        if (xmlFileList.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        for (XmlFile xmlFile : xmlFileList) {
            StringBuilder overdueTime = new StringBuilder();
            Object[][] parts = TimeUtils.getDateDiffParts(xmlFile.getCreateTime(), xmlFile.getUpdateTime());
            for (Object[] part : parts) {
                long value = (long) part[0];
                ChronoUnit unit = (ChronoUnit) part[1];
                overdueTime.append(value).append(unit.name());
            }
            msg.append("VIN: ")
                    .append(xmlFile.getVin())
                    .append(" ")
                    .append("于")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(xmlFile.getCreateTime()))
                    .append("生成，已超时")
                    .append(overdueTime)
                    .append(System.lineSeparator());
        }
        if(sentNotice(msg).getCode() == 200) {
            List<Long> xmlFileIds = xmlFileList.stream()
                    .map(XmlFile::getId)
                    .collect(Collectors.toList());
            if (xmlFileIds.isEmpty()) {
                return;
            }
            xmlFileMapper.updateXmlFileTimeoutUpload(xmlFileIds, 1);
        }
    }

    private R<?> sentNotice(StringBuilder msg){
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("XML文件超时未上传通知");
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        return remoteNoticeService.innerAdd(sysNotice);
    }
}
