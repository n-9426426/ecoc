package com.ruoyi.system.api.factory;

import com.ruoyi.system.api.RemoteTranslateService;
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
public class RemoteTranslateFallbackFactory implements FallbackFactory<RemoteTranslateService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteTranslateFallbackFactory.class);

    @Override
    public RemoteTranslateService create(Throwable throwable)
    {
        log.error("I18n翻译服务调用失败:{}", throwable.getMessage());

        String errorMessage = "Translation service call failed";

        return new RemoteTranslateService() {
            @Override
            public String translate(String langKey, String langCode) {
                return errorMessage;
            }
        };
    }
}
