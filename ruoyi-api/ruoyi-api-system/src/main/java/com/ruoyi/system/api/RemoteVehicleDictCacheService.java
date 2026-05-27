package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.api.factory.RemoteVehicleDictCacheServiceFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * vehicle 模块字典缓存清除服务（供其他模块跨服务调用）
 *
 * <p>当 system 模块字典数据发生变更（新增/修改/删除/刷新缓存）时，
 * 通过此 Feign 客户端通知 vehicle 服务清除本地 {@code dictCache}，
 * 确保 {@code DICT_MAP} 规则下次执行时能拿到最新字典数据。
 *
 * <p>放置位置：{@code ruoyi-vehicle-api} 模块，供 system 模块依赖并注入。
 */
@FeignClient(contextId = "remoteVehicleDictCacheService", value = ServiceNameConstants.VEHICLE_SERVICE,
        fallbackFactory = RemoteVehicleDictCacheServiceFallbackFactory.class)
public interface RemoteVehicleDictCacheService {

    /**
     * 清除 vehicle 服务中 ValueMappingParser 的字典缓存。
     *
     * @param dictType 要清除的字典类型；不传（null / 空）则清除全部缓存
     * @param source   调用来源标识，固定传 {@link SecurityConstants#INNER}
     */
    @DeleteMapping("/vehicle/dict/cache")
    AjaxResult evictDictCache(@RequestParam(value = "dictType", required = false) String dictType,
                              @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}