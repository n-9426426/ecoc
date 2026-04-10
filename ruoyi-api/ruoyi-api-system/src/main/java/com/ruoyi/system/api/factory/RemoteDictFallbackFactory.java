package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteDictService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class RemoteDictFallbackFactory implements FallbackFactory<RemoteDictService> {

    private static final Logger log = LoggerFactory.getLogger(RemoteDictFallbackFactory.class);

    @Override
    public RemoteDictService create(Throwable cause) {
        return dictType -> {
            log.error("查询字典数据失败，dictType：{}，原因：{}", dictType, cause.getMessage());
            return R.fail("查询字典数据失败：" + cause.getMessage());
        };
    }
}