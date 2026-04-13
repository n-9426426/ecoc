package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.factory.RemoteDictFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(
        contextId = "remoteDictService",
        value = ServiceNameConstants.SYSTEM_SERVICE,
        fallbackFactory = RemoteDictFallbackFactory.class
)
public interface RemoteDictService {

    /**
     * 根据字典类型查询字典数据
     */
    @GetMapping("/dict/data/type/{dictType}")
    R<List<SysDictData>> getDictDataByType(@PathVariable("dictType") String dictType);

    /**
     * 根据字典数据ID查询字典数据
     */
    @GetMapping("/dict/data/{dictCode}")
    R<SysDictData> getDataByDictCode(@PathVariable("dictCode") Long dictCode);
}