package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.domain.VehicleInfo;
import com.ruoyi.system.api.factory.RemoteVehicleFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(contextId = "remoteVehicleService", value = ServiceNameConstants.VEHICLE_SERVICE, fallbackFactory = RemoteVehicleFallbackFactory.class)
public interface RemoteVehicleService {
    @GetMapping("/info/{vehicleId}")
    public R<VehicleInfo> getVehicleInfo(@PathVariable("vehicleId") Long vehicleId, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
