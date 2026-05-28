package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.api.RemoteVehicleDictCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * {@link RemoteVehicleDictCacheService} 熔断降级工厂。
 *
 * <p>vehicle 服务不可用时，清缓存调用静默失败（仅打印 warn 日志），
 * 不影响 system 模块自身的字典刷新流程。
 *
 * <p>放置位置：{@code ruoyi-vehicle-api} 模块。
 */
@Component
public class RemoteVehicleDictCacheServiceFallbackFactory
        implements FallbackFactory<RemoteVehicleDictCacheService> {

    private static final Logger log =
            LoggerFactory.getLogger(RemoteVehicleDictCacheServiceFallbackFactory.class);

    @Override
    public RemoteVehicleDictCacheService create(Throwable cause) {
        return (dictType, source) -> {
            log.warn("[RemoteVehicleDictCacheService] 通知 vehicle 服务清缓存失败，" +
                    "dictType={}，原因：{}", dictType, cause.getMessage());
            // 降级时返回 error 但不抛异常，保证 system 模块 refreshCache 正常完成
            return AjaxResult.error("vehicle 服务不可用，字典缓存清除已跳过");
        };
    }
}