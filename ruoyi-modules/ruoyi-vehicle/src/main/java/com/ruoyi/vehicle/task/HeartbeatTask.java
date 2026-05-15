package com.ruoyi.vehicle.task;

import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.mapper.SysAccountConfigMapper;
import com.ruoyi.vehicle.service.HeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 心跳监测调度器
 *
 * 心跳频率从 application.yml 配置（支持按国家自定义频率请接入 xxl-job 等动态任务框架）：
 * heartbeat:
 *   normal-interval-ms: 30000   # 正常检测间隔，默认30s
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatTask {

    private final SysAccountConfigMapper accountConfigMapper;
    private final HeartbeatService heartbeatService;

    /** 并发检测线程池 */
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * 全量心跳检测
     * fixedRateString 读取 yml 配置，默认30s
     */
    @Scheduled(fixedRateString = "${heartbeat.normal-interval-ms:30000}")
    public void heartbeatCheck() {
        List<SysAccountConfig> accounts = accountConfigMapper.selectEnabledAndAvailable();
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        log.debug("[心跳调度] 本轮检测账号数: {}", accounts.size());
        for (SysAccountConfig account : accounts) {
            executor.submit(() -> {
                try {
                    heartbeatService.doHeartbeat(account);
                } catch (Exception e) {
                    log.error("[心跳调度] 检测异常: accountId={}, msg={}",
                            account.getId(), e.getMessage(), e);
                }
            });
        }
    }

    /**
     * 每天凌晨2点清理90天前的心跳日志
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpiredLogs() {
        log.info("[日志清理] 开始清理心跳过期日志");
        heartbeatService.cleanExpiredLogs();
    }
}
