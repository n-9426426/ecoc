package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteLoginService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.LoginBody;
import com.ruoyi.system.api.enums.FileTypeEnum;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/vehicle")
public class VehicleInfoController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoController.class);

    @Autowired
    private IVehicleInfoService vehicleInfoService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    @Autowired
    private RemoteLoginService remoteLoginService;

    @Operation(summary = "MES数据推送至本系统")
    @Log(title = "数据推送", businessType = BusinessType.INSERT)
    @PostMapping("/to-system")
    public AjaxResult MesToSystem(VehicleDto vehicleDto) {
        LoginBody body = new LoginBody();
        body.setUsername(vehicleDto.getUsername());
        body.setPassword(vehicleDto.getPassword());
        int loginResultCode = remoteLoginService.login(body).getCode();
        if (loginResultCode != 200) {
            throw new ServiceException("MES数据推送至本系统时用户名密码错误");
        }
        vehicleInfoService.getVehicleInfoFromMes(vehicleDto);
        return AjaxResult.success();
    }

    /**
     * 查询车辆信息列表
     */
    @Operation(summary = "车辆信息列表")
    @RequiresPermissions("vehicle:info:list")
    @GetMapping("/list")
    public TableDataInfo list(VehicleInfo vehicleInfo) {
        startPage();
        List<VehicleInfo> list = vehicleInfoService.selectVehicleInfoList(vehicleInfo);
        return getDataTable(list);
    }

    /**
     * 获取车辆信息详细信息
     */
    @Operation(summary = "车辆信息详情")
    @RequiresPermissions("vehicle:info:query")
    @GetMapping(value = "/{vehicleId}")
    public AjaxResult getInfo(@PathVariable("vehicleId") Long vehicleId) {
        return AjaxResult.success(vehicleInfoService.selectVehicleInfoById(vehicleId));
    }

    @Operation(summary = "更新车辆信息状态")
    @RequiresPermissions("system:role:edit")
    @Log(title = "车辆信息管理", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody VehicleInfo vehicleInfo)
    {
        return toAjax(vehicleInfoService.updateStatus(vehicleInfo));
    }

    /**
     * 新增车辆信息
     */
    @Operation(summary = "新增车辆信息")
    @RequiresPermissions("vehicle:info:add")
    @Log(title = "车辆信息管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody VehicleInfo vehicleInfo) {
        return AjaxResult.success(vehicleInfoService.insertVehicleInfo(vehicleInfo));
    }

    @Operation(summary = "校验车辆信息")
    @RequiresPermissions("vehicle:info:validation")
    @Log(title = "车辆信息管理", businessType = BusinessType.VALIDATION)
    @PostMapping("/validation")
    public AjaxResult validation(@RequestBody Long vehicleInfoId) {
        return AjaxResult.success(vehicleInfoService.validateVehicleInfo(vehicleInfoId));
    }

    /**
     * 修改车辆信息
     */
    @Operation(summary = "修改信息列表")
    @RequiresPermissions("vehicle:info:edit")
    @Log(title = "车辆信息管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody VehicleInfo vehicleInfo) {
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setUploadStatus(0);
        return AjaxResult.success(vehicleInfoService.updateVehicleInfo(vehicleInfo));
    }

    /**
     * 删除车辆信息
     */
    @Operation(summary = "删除车辆信息")
    @RequiresPermissions("vehicle:info:remove")
    @Log(title = "车辆信息管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{vehicleIds}")
    public AjaxResult remove(@PathVariable Long[] vehicleIds) {
        return AjaxResult.success(vehicleInfoService.deleteVehicleInfoByIds(vehicleIds));
    }

    /**
     * 恢复车辆信息
     */
    @Operation(summary = "恢复车辆信息")
    @RequiresPermissions("vehicle:info:restore")
    @Log(title = "车辆信息管理", businessType = BusinessType.RESTORE)
    @PutMapping("/restore")
    public AjaxResult recover(@RequestBody Long[] vehicleIds) {
        return vehicleInfoService.restoreVehicleInfoByIds(vehicleIds);
    }

    /**
     * 永久删除车辆信息
     */
    @Operation(summary = "永久删除车辆信息")
    @RequiresPermissions("vehicle:info:permanently")
    @Log(title = "车辆信息管理", businessType = BusinessType.PERMANENTLY_DELETE)
    @PutMapping("/permanently")
    public AjaxResult permanently(@RequestBody Long[] vehicleIds) {
        return AjaxResult.success(vehicleInfoService.permanentlyDeleteVehicleInfoByIds(vehicleIds));
    }

    @Operation(summary = "导入excel")
    @RequiresPermissions("vehicle:info:import")
    @Log(title = "车辆信息管理", businessType = BusinessType.IMPORT)
    @PostMapping("/upload/excel")
    public AjaxResult uploadExcelFile(@RequestParam("file") MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            if (file.isEmpty() || fileName == null) {
                return AjaxResult.error(remoteTranslateService.translate("common.upload.file.empty", null));
            }

            String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

            FileTypeEnum fileType = FileTypeEnum.getByExtension(extension);
            if (!(fileType == FileTypeEnum.EXCEL)) {
                return AjaxResult.error(remoteTranslateService.translate("common.upload.file.type.unsupported", null));
            }
            return vehicleInfoService.importExcel(file);
        } catch (Exception e) {
            log.error("文件导入失败", e);
            return AjaxResult.error("文件导入失败: " + e.getMessage());
        }
    }
}
