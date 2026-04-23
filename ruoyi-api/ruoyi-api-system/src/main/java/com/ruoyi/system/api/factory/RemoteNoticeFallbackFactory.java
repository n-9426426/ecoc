package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysNotice;
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
public class RemoteNoticeFallbackFactory implements FallbackFactory<RemoteNoticeService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteNoticeFallbackFactory.class);

    @Override
    public RemoteNoticeService create(Throwable throwable)
    {
        log.error("通知服务调用失败:{}", throwable.getMessage());
        return new RemoteNoticeService() {
            @Override
            public R<?> add(SysNotice notice) {
                return R.fail(throwable.getMessage());
            }
        };
    }
}
