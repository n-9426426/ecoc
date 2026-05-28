package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.vehicle.service.IVehicleTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * vehicle 模块内部接口：字典缓存管理。
 *
 * <p>仅接受来自内部服务（{@code X-Source: inner}）的调用，
 * 对外网关不应暴露此路径。
 *
 * <p>放置位置：{@code ruoyi-vehicle} 模块。
 */
@RestController
@RequestMapping("/vehicle/dict")
public class VehicleDictCacheController {

    @Autowired
    private IVehicleTemplateService vehicleTemplateService;

    /**
     * 清除 ValueMappingParser 字典缓存（由 system 模块刷新字典时调用）。
     *
     * @param dictType 指定字典类型；不传则清除全部
     * @param source   内部调用标识，须为 {@link SecurityConstants#INNER}
     */
    @DeleteMapping("/cache")
    public AjaxResult evictDictCache(
            @RequestParam(value = "dictType", required = false) String dictType,
            @RequestHeader(SecurityConstants.FROM_SOURCE) String source) {

        if (!SecurityConstants.INNER.equals(source)) {
            return AjaxResult.error("非法调用");
        }
        vehicleTemplateService.evictDictCache(dictType);
        return AjaxResult.success();
    }
}