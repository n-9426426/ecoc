package com.ruoyi.vehicle.service.impl;

import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.domain.SysHeartbeatConfig;
import com.ruoyi.vehicle.domain.SysHeartbeatLog;
import com.ruoyi.vehicle.domain.SysHeartbeatLogQuery;
import com.ruoyi.vehicle.mapper.SysAccountConfigMapper;
import com.ruoyi.vehicle.mapper.SysHeartbeatConfigMapper;
import com.ruoyi.vehicle.mapper.SysHeartbeatLogMapper;
import com.ruoyi.vehicle.service.HeartbeatService;
import com.ruoyi.vehicle.utils.HeartbeatExceptionTypeEnum;
import com.ruoyi.vehicle.utils.HeartbeatStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 心跳监测 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatServiceImpl implements HeartbeatService {

    private final SysAccountConfigMapper accountConfigMapper;
    private final SysHeartbeatLogMapper heartbeatLogMapper;
    private final SysHeartbeatConfigMapper heartbeatConfigMapper;

    // ----------------------------------------------------------------
    //  心跳入口
    // ----------------------------------------------------------------

    @Override
    public void doHeartbeat(SysAccountConfig account) {
        SysHeartbeatConfig config = heartbeatConfigMapper
                .selectByCountryOrDefault(account.getCountryCode());

        HeartbeatResult result = sendHeartbeatRequest(account, config);

        // 写日志
        heartbeatLogMapper.insert(buildLog(account, result, false, null, null));

        if (result.success) {
            accountConfigMapper.updateHeartbeatStatus(account.getId(),
                    HeartbeatStatusEnum.NORMAL.getCode());
            // 接口之前不可用 → 自动恢复
            if (Integer.valueOf(0).equals(account.getApiAvailable())) {
                accountConfigMapper.updateApiAvailable(account.getId(), 1);
                log.info("[心跳] 接口恢复可用: accountId={}", account.getId());
            }
        } else {
            log.warn("[心跳] 检测异常: accountId={}, reason={}", account.getId(), result.exceptionType);
            accountConfigMapper.updateHeartbeatStatus(account.getId(),
                    HeartbeatStatusEnum.EXCEPTION.getCode());
            doReconnect(account);
        }
    }

    // ----------------------------------------------------------------
    //  重连流程
    // ----------------------------------------------------------------

    @Override
    public void doReconnect(SysAccountConfig account) {
        SysHeartbeatConfig config = heartbeatConfigMapper
                .selectByCountryOrDefault(account.getCountryCode());

        int maxTimes   = config.getMaxReconnectTimes();         // 默认3次
        int intervalMs = config.getReconnectInterval() * 1000;  // 默认5s
        int pauseMs    = config.getReconnectPause() * 1000;     // 默认60s

        accountConfigMapper.updateHeartbeatStatus(account.getId(),
                HeartbeatStatusEnum.RECONNECTING.getCode());

        int round = 0;
        while (true) {
            round++;
            log.info("[重连] 第{}轮开始: accountId={}", round, account.getId());
            boolean roundSuccess = false;

            for (int i = 1; i <= maxTimes; i++) {
                sleep(intervalMs);

                HeartbeatResult result = sendHeartbeatRequest(account, config);

                // 登录失效 → 先重新登录再试
                if (HeartbeatExceptionTypeEnum.LOGIN_EXPIRED.getCode()
                        .equals(result.exceptionType)) {
                    if (reLogin(account)) {
                        result = sendHeartbeatRequest(account, config);
                    }
                }

                // 写重连日志
                heartbeatLogMapper.insert(buildLog(account, result, true, i, result.success ? 0 : 1));

                if (result.success) {
                    log.info("[重连] 成功: accountId={}, 第{}轮第{}次", account.getId(), round, i);
                    accountConfigMapper.updateHeartbeatStatus(account.getId(),
                            HeartbeatStatusEnum.NORMAL.getCode());
                    accountConfigMapper.updateApiAvailable(account.getId(), 1);
                    roundSuccess = true;
                    break;
                }
            }

            if (roundSuccess) break;

            // 通知检查
            checkAndNotify(account, config);

            // 是否超过禁用阈值
            int exCount = heartbeatLogMapper
                    .countExceptionInMinutes(account.getId(), config.getDisableDuration());
            if (exCount > 0) {
                accountConfigMapper.updateApiAvailable(account.getId(), 0);
                log.error("[重连] 接口持续失败超{}分钟，已标记不可用: accountId={}",
                        config.getDisableDuration(), account.getId());
                break;
            }

            log.warn("[重连] 第{}轮失败，暂停{}ms后重试", round, pauseMs);
            sleep(pauseMs);
        }
    }

    // ----------------------------------------------------------------
    //  日志查询 & 清理
    // ----------------------------------------------------------------

    @Override
    public List<SysHeartbeatLog> selectLogList(SysHeartbeatLogQuery query) {
        return heartbeatLogMapper.selectList(query);
    }

    @Override
    public void cleanExpiredLogs() {
        int deleted = heartbeatLogMapper.deleteExpiredLogs();
        log.info("[日志清理] 删除{}条90天前心跳日志", deleted);
    }

    // ----------------------------------------------------------------
    //  私有工具
    // ----------------------------------------------------------------

    /**
     * 发送心跳请求（优先备用接口）
     * TODO: 替换为真实 HTTP 调用（RestTemplate / OkHttp / Feign 等）
     */
    private HeartbeatResult sendHeartbeatRequest(SysAccountConfig account,
                                                 SysHeartbeatConfig config) {
        String backupUrl = account.getBackupApiUrl();
        String url = (backupUrl != null && !backupUrl.trim().isEmpty())
                ? backupUrl
                : account.getApiUrl();
        try {
            // int timeoutMs = config.getTimeoutSeconds() * 1000;
            // ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            // if (resp.getStatusCode().is2xxSuccessful()) return HeartbeatResult.ok(url, resp.getStatusCodeValue());
            // return HeartbeatResult.fail(url, resp.getStatusCodeValue(), HTTP_ERROR, "非200");
            return HeartbeatResult.ok(url, 200); // 占位，替换为真实实现
        } catch (Exception e) {
            String type = e.getMessage() != null && e.getMessage().contains("timeout")
                    ? HeartbeatExceptionTypeEnum.TIMEOUT.getCode()
                    : HeartbeatExceptionTypeEnum.NETWORK_ERROR.getCode();
            return HeartbeatResult.fail(url, null, type, e.getMessage());
        }
    }

    /**
     * 账号重新登录
     * TODO: 替换为对应国家接口的登录 API 调用
     */
    private boolean reLogin(SysAccountConfig account) {
        log.info("[重连] 账号重新登录: accountId={}, account={}", account.getId(), account.getAccount());
        // TODO: 调用国家接口登录 API，成功返回 true
        return false;
    }

    private void checkAndNotify(SysAccountConfig account, SysHeartbeatConfig config) {
        int exMinutes     = heartbeatLogMapper.countExceptionInMinutes(account.getId(), config.getNotifyDuration());
        int reconnectTimes = heartbeatLogMapper.countReconnectTimes(account.getId());
        if (exMinutes > 0 || reconnectTimes >= config.getNotifyReconnectTimes()) {
            log.warn("[告警] accountId={} 异常超{}分钟或重连超{}次，需人工介入",
                    account.getId(), config.getNotifyDuration(), config.getNotifyReconnectTimes());
            // TODO: 调用消息/邮件通知服务
        }
    }

    private SysHeartbeatLog buildLog(SysAccountConfig account, HeartbeatResult result,
                                     boolean isReconnect, Integer reconnectCount, Integer reconnectResult) {
        return SysHeartbeatLog.builder()
                .accountId(account.getId())
                .countryCode(account.getCountryCode())
                .apiUrl(result.usedUrl)
                .checkTime(LocalDateTime.now())
                .checkResult(result.success ? 0 : 1)
                .responseCode(result.httpCode)
                .exceptionType(result.exceptionType)
                .exceptionMsg(result.exceptionMsg)
                .isReconnect(isReconnect ? 1 : 0)
                .reconnectCount(reconnectCount)
                .reconnectResult(reconnectResult)
                .build();
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ----------------------------------------------------------------
    //  内部结果封装
    // ----------------------------------------------------------------

    private static class HeartbeatResult {
        boolean success;
        String  usedUrl;
        Integer httpCode;
        String  exceptionType;
        String  exceptionMsg;

        static HeartbeatResult ok(String url, int code) {
            HeartbeatResult r = new HeartbeatResult();
            r.success = true; r.usedUrl = url; r.httpCode = code;
            return r;
        }

        static HeartbeatResult fail(String url, Integer code, String type, String msg) {
            HeartbeatResult r = new HeartbeatResult();
            r.success = false; r.usedUrl = url; r.httpCode = code;
            r.exceptionType = type; r.exceptionMsg = msg;
            return r;
        }
    }
}
