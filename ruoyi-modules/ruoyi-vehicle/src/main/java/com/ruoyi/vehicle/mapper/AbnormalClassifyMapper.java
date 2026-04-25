package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.AbnormalClassify;

import java.util.List;

public interface AbnormalClassifyMapper {

    /**
     * 批量插入异常分类记录
     *
     * @param list 异常分类列表
     * @return 影响行数
     */
    int batchInsert(List<AbnormalClassify> list);

    /**
     * 根据条件查询异常分类列表
     *
     * @param query 查询条件
     * @return 异常分类列表
     */
    List<AbnormalClassify> selectByCondition(AbnormalClassify query);
}
