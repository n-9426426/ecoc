package com.ruoyi.vehicle.mapper;
import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.domain.SysAccountConfigQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 账号管理 Mapper
 * 纯 MyBatis，无 MyBatis-Plus 依赖
 */
@Mapper
public interface SysAccountConfigMapper {

    /**
     * 列表查询（配合 PageHelper 使用）
     */
    List<SysAccountConfig> selectList(SysAccountConfigQuery query);

    /**
     * 按主键查询
     */
    SysAccountConfig selectById(@Param("id") Long id);

    /**
     * 查询所有启用且接口可用的账号（心跳调度使用）
     */
    List<SysAccountConfig> selectEnabledAndAvailable();

    /**
     * 新增
     */
    int insert(SysAccountConfig entity);

    /**
     * 按主键更新（只更新非null字段）
     */
    int updateById(SysAccountConfig entity);

    /**
     * 更新心跳状态 + 最后心跳时间
     */
    int updateHeartbeatStatus(@Param("id") Long id,
                              @Param("heartbeatStatus") Integer heartbeatStatus);

    /**
     * 更新接口可用标记
     */
    int updateApiAvailable(@Param("id") Long id,
                           @Param("apiAvailable") Integer apiAvailable);

    /**
     * 逻辑删除
     */
    int deleteById(@Param("id") Long id);
}
