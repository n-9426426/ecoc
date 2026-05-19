package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.Breakpoint;

import java.util.List;

/**
 * 断点管理 Mapper 接口
 *
 * @author ruoyi
 */
public interface BreakpointMapper {

    /**
     * 查询断点
     *
     * @param id 主键
     * @return 断点
     */
    Breakpoint selectBreakpointById(Long id);

    /**
     * 查询断点列表
     *
     * @param breakpoint 断点
     * @return 断点集合
     */
    List<Breakpoint> selectBreakpointList(Breakpoint breakpoint);

    /**
     * 新增断点
     *
     * @param breakpoint 断点
     * @return 结果
     */
    int insertBreakpoint(Breakpoint breakpoint);

    /**
     * 修改断点
     *
     * @param breakpoint 断点
     * @return 结果
     */
    int updateBreakpoint(Breakpoint breakpoint);

    /**
     * 批量删除断点
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    int deleteBreakpointByIds(Long[] ids);
}
