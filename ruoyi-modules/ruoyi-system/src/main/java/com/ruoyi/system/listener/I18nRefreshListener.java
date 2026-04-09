package com.ruoyi.system.listener;


import com.ruoyi.system.service.ISysI18nService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class I18nRefreshListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(I18nRefreshListener.class);

    @Autowired
    private ISysI18nService i18nService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("【i18n】收到分布式刷新通知，开始重建本节点本地缓存...");
        try {
            // 只重建本地 JVM 缓存，不再操作 Redis（避免循环发布）
            i18nService.reloadLocalCache();
            log.info("【i18n】本节点本地缓存重建完成");
        } catch (Exception e) {
            log.error("【i18n】本节点缓存重建失败：{}", e.getMessage(), e);
        }
    }
}




