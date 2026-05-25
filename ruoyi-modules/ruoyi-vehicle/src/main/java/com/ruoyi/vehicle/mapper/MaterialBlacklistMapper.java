package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.MaterialBlacklist;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 物料黑名单 Mapper 接口
 */
public interface MaterialBlacklistMapper {

    /**
     * 查询物料黑名单列表
     */
    List<MaterialBlacklist> selectMaterialBlacklistList(MaterialBlacklist materialBlacklist);

    /**
     * 根据ID查询物料黑名单
     */
    MaterialBlacklist selectMaterialBlacklistById(Long id);

    /**
     * 新增物料黑名单
     */
    int insertMaterialBlacklist(MaterialBlacklist materialBlacklist);

    /**
     * 修改物料黑名单
     */
    int updateMaterialBlacklist(MaterialBlacklist materialBlacklist);

    /**
     * 批量删除物料黑名单
     */
    int deleteMaterialBlacklistByIds(Long[] ids);

    /**
     * 根据物料号查询黑名单记录
     */
    MaterialBlacklist selectMaterialBlacklistByMaterialNo(String materialNo);

    /**
     * 更新物料黑名单状态
     */
    int updateMaterialBlacklistStatus(@Param("id") Long id, @Param("status") Integer status, @Param("updateBy") String updateBy);
}
