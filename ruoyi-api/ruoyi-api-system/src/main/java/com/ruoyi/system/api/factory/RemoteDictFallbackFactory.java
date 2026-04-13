package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RemoteDictFallbackFactory implements FallbackFactory<RemoteDictService> {

    private static final Logger log = LoggerFactory.getLogger(RemoteDictFallbackFactory.class);

    @Override
    public RemoteDictService create(Throwable cause) {
        return new RemoteDictService() {

            @Override
            public R<List<SysDictData>> getDictDataByType(String dictType) {
                log.error("查询字典数据失败，dictType：{}，原因：{}", dictType, cause.getMessage());
                return R.fail("查询字典数据失败：" + cause.getMessage());
            }

            @Override
            public R<SysDictData> getDataByDictCode(Long dictCode) {
                log.error("查询字典数据失败，dictCode：{}，原因：{}", dictCode, cause.getMessage());
                return R.fail("查询字典数据失败：" + cause.getMessage());
            }
        };
    }
}