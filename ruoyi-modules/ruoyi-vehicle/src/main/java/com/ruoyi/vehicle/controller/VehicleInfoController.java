package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteLoginService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.LoginBody;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            throw new ServiceException("MES数据推送至本系统时用户名或密码错误");
        }
        vehicleInfoService.getVehicleInfoFromMes(vehicleDto);
        return AjaxResult.success();
    }

    /**
     * 查询车辆信息列表
     */
    @GetMapping("/list")
    @RequiresPermissions("vehicle:info:query")
    public TableDataInfo list(VehicleInfo vehicleInfo) {
        // 手动输入的vin按逗号/换行拆分成vinList
        if (StringUtils.isNotBlank(vehicleInfo.getVin())) {
            List<String> vinList = Arrays.stream(vehicleInfo.getVin().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setVinList(vinList);
            vehicleInfo.setVin(null); // 清掉vin，走vinList的IN查询
        }
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
    @RequiresPermissions("vehicle:info:edit")
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
    public AjaxResult validation(@RequestBody List<Long> vehicleInfoId) {
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
        // 用户手动编辑时才重置校验状态和上传状态
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

    @RequiresPermissions("vehicle:info:import")
    @PostMapping("/upload/excel")
    public R<Void> importExcel(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return R.fail("上传文件不能为空");
        }
        vehicleInfoService.importVehicleInfoFromExcel(file);
        return R.ok();
    }

    /**
     * 获取所有物料号列表（下拉框用）
     */
    @GetMapping("/material/options")
    public AjaxResult getMaterialOptions() {
        List<String> list = vehicleInfoService.selectAllMaterialNos();
        return AjaxResult.success(list);
    }

    /**
     * 根据物料号查模板关联信息（选择物料号后自动带出）
     */
    @GetMapping("/material/template/{materialNo}")
    public AjaxResult getTemplateByMaterialNo(@PathVariable("materialNo") String materialNo) {
        Long templateId = vehicleInfoService
                .selectVehicleTemplateIdByMaterialNo(materialNo);
        if (templateId == null) {
            return AjaxResult.error("该物料号未关联任何模板");
        }
        VehicleTemplate template = vehicleInfoService.selectVehicleTemplateById(templateId);
        if (template == null) {
            return AjaxResult.error("关联模板不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("vehicleTemplateId", templateId);
        result.put("wvtaNo", template.getWvtaCocNo());
        result.put("cocTemplateNo", template.getCocTemplateNo());
        result.put("json", template.getJson());
        return AjaxResult.success(result);
    }
}
