package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.Breakpoint;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.BreakpointMapper;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.service.IBreakpointService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 断点管理 Service 业务层实现
 *
 * @author ruoyi
 */
@Service
public class BreakpointServiceImpl implements IBreakpointService {

    @Autowired
    private BreakpointMapper breakpointMapper;

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    /**
     * 查询断点
     */
    @Override
    public Breakpoint selectBreakpointById(Long id) {
        Breakpoint breakpoint = breakpointMapper.selectBreakpointById(id);
        breakpoint.setVehicleInfos(vehicleInfoMapper.selectVehicleInfoByVinManufactureDate(breakpoint.getVin()));
        return breakpoint;
    }

    /**
     * 查询断点列表
     */
    @Override
    public List<Breakpoint> selectBreakpointList(Breakpoint breakpoint) {
        return breakpointMapper.selectBreakpointList(breakpoint);
    }

    /**
     * 新增断点
     */
    @Override
    public int insertBreakpoint(Breakpoint breakpoint) {
        VehicleInfo v = vehicleInfoMapper.selectVehicleInfoByVin(breakpoint.getVin());
        if (v.getManufactureDate() == null) {
            throw new RuntimeException("该VIN无制造时间，无法创建断点信息");
        }
        List<VehicleInfo> vehicleInfoList = breakpoint.getVehicleInfos();
        for (VehicleInfo vehicleInfo : vehicleInfoList) {
            vehicleInfoMapper.updateTempVersionByVin(vehicleInfo.getVin(), vehicleInfo.getTempVersion());
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        breakpoint.setCreateBy(loginUser.getUsername());
        breakpoint.setCreateTime(new Date());
        return breakpointMapper.insertBreakpoint(breakpoint);
    }

    /**
     * 修改断点
     */
    @Override
    public int updateBreakpoint(Breakpoint breakpoint) {
        List<VehicleInfo> vehicleInfoList = breakpoint.getVehicleInfos();
        for (VehicleInfo vehicleInfo : vehicleInfoList) {
            vehicleInfoMapper.updateTempVersionByVin(vehicleInfo.getVin(), vehicleInfo.getTempVersion());
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        breakpoint.setUpdateBy(loginUser.getUsername());
        breakpoint.setUpdateTime(new Date());
        return breakpointMapper.updateBreakpoint(breakpoint);
    }

    /**
     * 批量删除断点
     */
    @Override
    public int deleteBreakpointByIds(Long[] ids) {
        return breakpointMapper.deleteBreakpointByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int disposeBreakpoint(Breakpoint breakpoint) {
        breakpoint.setIsDispose("1");
        LoginUser loginUser = SecurityUtils.getLoginUser();
        breakpoint.setDisposeUser(loginUser.getUsername());
        breakpoint.setDisposeTime(new Date());
        List<VehicleInfo> vehicleInfoList = breakpoint.getVehicleInfos() == null ? new ArrayList<>() : breakpoint.getVehicleInfos();
        List<String> vinList = new ArrayList<>();
        if (!vehicleInfoList.isEmpty()) {
            vinList = breakpoint.getVehicleInfos().stream()
                    .map(VehicleInfo::getVin)
                    .collect(Collectors.toList());
        }
        vehicleInfoMapper.updateVehicleTemplateIdByTempVersion(breakpoint.getId(), vinList);
        return breakpointMapper.updateBreakpoint(breakpoint);
    }
}
