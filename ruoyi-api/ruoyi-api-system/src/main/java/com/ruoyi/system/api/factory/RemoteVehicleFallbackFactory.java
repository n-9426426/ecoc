package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteVehicleService;
import com.ruoyi.system.api.domain.VehicleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 用户服务降级处理
 * 
 * @author ruoyi
 */
@Component
public class RemoteVehicleFallbackFactory implements FallbackFactory<RemoteVehicleService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteVehicleFallbackFactory.class);

    @Override
    public RemoteVehicleService create(Throwable throwable)
    {
        log.error("车辆信息服务调用失败:{}", throwable.getMessage());

        return new RemoteVehicleService() {
            @Override
            public R<VehicleInfo> getVehicleInfo(Long vehicleId, String source) {
                return R.fail("获取车辆信息失败:" + throwable.getMessage());
            }
        };
    }
}
