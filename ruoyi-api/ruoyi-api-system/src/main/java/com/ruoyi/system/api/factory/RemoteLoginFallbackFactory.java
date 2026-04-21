package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteLoginService;
import com.ruoyi.system.api.domain.LoginBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 日志服务降级处理
 * 
 * @author ruoyi
 */
@Component
public class RemoteLoginFallbackFactory implements FallbackFactory<RemoteLoginService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteLoginFallbackFactory.class);

    @Override
    public RemoteLoginService create(Throwable throwable)
    {
        log.error("日志服务调用失败:{}", throwable.getMessage());
        return new RemoteLoginService() {
            @Override
            public R<?> login(LoginBody body) {
                return R.fail();
            }
        };
    }
}
