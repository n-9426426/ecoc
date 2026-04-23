package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.factory.RemoteNoticeFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteNoticeService", value = ServiceNameConstants.SYSTEM_SERVICE, fallbackFactory = RemoteNoticeFallbackFactory.class)
public interface RemoteNoticeService {
    @PostMapping("/notice")
    public R<?> add(@Validated @RequestBody SysNotice notice);
}
