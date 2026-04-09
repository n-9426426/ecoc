package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.system.api.factory.RemoteTranslateFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteTranslateService", value = ServiceNameConstants.SYSTEM_SERVICE, fallbackFactory = RemoteTranslateFallbackFactory.class)
public interface RemoteTranslateService {
    @GetMapping("/i18n/translate")
    String translate(@RequestParam(value = "langKey") String langKey, @RequestParam(value = "langCode", required = false) String langCode);
}
