package com.ruoyi.vehicle.controller;

import com.alibaba.fastjson2.util.DateUtils;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.JwtUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.datascope.annotation.DataScope;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteLoginService;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.LoginBody;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
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
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private RemoteLoginService remoteLoginService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ExcelUtil excelUtil;


    @Autowired
    private IFirstVehicleCheckService firstVehicleCheckService;

    @Operation(summary = "MES数据推送至本系统")
    @Log(title = "数据推送", businessType = BusinessType.INSERT)
    @PostMapping("/to-system")
    public AjaxResult MesToSystem(@RequestBody VehicleDto vehicleDto) {
        LoginBody body = new LoginBody();
        body.setUsername(vehicleDto.getUsername());
        body.setPassword(vehicleDto.getPassword());
        R<?> loginResult = remoteLoginService.login(body);
        if (loginResult.getCode() != 200) {
            throw new ServiceException(loginResult.getMsg());
        }

        String token = (String) loginResult.getData();
        SysUser sysUser = new SysUser();
        sysUser.setUserId(Long.valueOf(JwtUtils.getUserId(token)));
        sysUser.setUserName(JwtUtils.getUserName(token));
        LoginUser loginUser = new LoginUser();
        loginUser.setSysUser(sysUser);

        // 从 token 里或远程加载权限
        // ruoyi 的 TokenService 可以根据 token 拿到完整的 LoginUser
        LoginUser fullLoginUser = tokenService.getLoginUser(token);
        if (fullLoginUser == null) {
            throw new ServiceException("登录信息获取失败");
        }

        // 用完整的 loginUser 往下传
        List<Map<String, Object>> result = new LinkedList<>();
        Date now = new Date();
        for (VehicleDto.Vehicle vehicle : vehicleDto.getVehicles()) {
            Map<String, Object> resultItem = new LinkedHashMap<>();
            try {
                resultItem = vehicleInfoService.getVehicleInfoFromMes(vehicle, now, fullLoginUser);
                result.add(resultItem);
            } catch (Exception e) {
                resultItem.put("vin", vehicle.getVin());
                resultItem.put("recordId", null);
                resultItem.put("receiveTime", DateUtils.format(now, "yyyy-MM-dd HH:mm:ss"));
                resultItem.put("cause", e.getMessage());
            }
        }
        return AjaxResult.success(result);
    }
    /**
     * 查询车辆信息列表
     */
    @GetMapping("/list")
    @RequiresPermissions("vehicle:info:query")
    @DataScope(tableAlias = "vi")
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
        if (StringUtils.isNotBlank(vehicleInfo.getMaterialNo())) {
            List<String> materialNoList = Arrays.stream(vehicleInfo.getMaterialNo().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setMaterialNoList(materialNoList);
            vehicleInfo.setMaterialNo(null);
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
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setUploadStatus(0);
        vehicleInfoService.updateVehicleInfo(vehicleInfo, true);
        // handleAfterUpdate 内部自行查旧数据，只有物料号或模版变化时才触发重算
        firstVehicleCheckService.handleAfterUpdate(vehicleInfo);
        return AjaxResult.success();
    }

    /**
     * 新增车辆信息
     */
    @Operation(summary = "新增车辆信息")
    @RequiresPermissions("vehicle:info:add")
    @Log(title = "车辆信息管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody VehicleInfo vehicleInfo) {
        vehicleInfoService.insertVehicleInfo(vehicleInfo);
        firstVehicleCheckService.handleAfterInsert(Collections.singletonList(vehicleInfo));
        return AjaxResult.success(vehicleInfo);
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
        return AjaxResult.success(vehicleInfoService.updateVehicleInfo(vehicleInfo, true));
    }
    /**
     * 删除车辆信息
     */
    @Operation(summary = "删除车辆信息")
    @RequiresPermissions("vehicle:info:remove")
    @Log(title = "车辆信息管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{vehicleIds}")
    public AjaxResult remove(@PathVariable Long[] vehicleIds) {
        List<VehicleInfo> snapshot = vehicleInfoService.selectVehicleInfoByIds(vehicleIds);
        vehicleInfoService.deleteVehicleInfoByIds(vehicleIds);
        firstVehicleCheckService.handleAfterDelete(snapshot);
        return AjaxResult.success();
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

    @RequiresPermissions("vehicle:info:export")
    @PostMapping("/export/excel")
    public void exportExcel(HttpServletResponse response, @RequestBody VehicleInfo vehicleInfo) throws Exception {
        List<VehicleInfo> list = vehicleInfoService.selectVehicleInfoList(vehicleInfo);
        excelUtil.exportExcel(response, list, "vehicle_info", "Vehicle");
    }

    @GetMapping("/download/vin")
    public void downloadVinTemplate(HttpServletResponse response) throws IOException {
        download("车辆信息VIN查询模版.xlsx", response);
    }

    @GetMapping("/download/materialNo")
    public void downloadMaterialNoTemplate(HttpServletResponse response) throws IOException {
        download("车辆信息物料号查询模版.xlsx", response);
    }

    @GetMapping("/download/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        download("车辆信息模版.xlsx", response);
    }

    private void download(String fileName, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));

        ClassPathResource resource = new ClassPathResource("assets/" + fileName);
        IOUtils.copy(resource.getInputStream(), response.getOutputStream());
        response.flushBuffer();
    }



    /**
     * 首台车待确认列表（物料号维度 + 模版维度统一接口）
     *
     * <p>通过 dimension 参数区分维度：
     * <ul>
     *   <li>material：物料号维度，查 first_material_flag=1 AND generate_affirm=0</li>
     *   <li>template：模版维度，查 first_template_flag=1 AND upload_affirm=0</li>
     * </ul>
     *
     * <p>其余查询条件与车辆列表保持一致，支持 VIN、物料号、模版号等过滤。
     */
    @Operation(summary = "首台车待确认列表")
    @RequiresPermissions("vehicle:first:query")
    @GetMapping("/first/list")
    public TableDataInfo firstVehicleList(
            VehicleInfo vehicleInfo,
            @RequestParam(value = "dimension", defaultValue = "material") String dimension) {

        if (!"material".equals(dimension) && !"template".equals(dimension)) {
            return getDataTable(Collections.emptyList());
        }

        // VIN 支持逗号/换行分隔批量查询
        if (StringUtils.isNotBlank(vehicleInfo.getVin())) {
            List<String> vinList = Arrays.stream(vehicleInfo.getVin().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setVinList(vinList);
            vehicleInfo.setVin(null);
        }
        // 物料号支持逗号/换行分隔批量查询
        if (StringUtils.isNotBlank(vehicleInfo.getMaterialNo())) {
            List<String> materialNoList = Arrays.stream(vehicleInfo.getMaterialNo().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setMaterialNoList(materialNoList);
            vehicleInfo.setMaterialNo(null);
        }

        startPage();
        List<VehicleInfo> list = vehicleInfoService.listFirstVehicleUnconfirmed(vehicleInfo, dimension);
        return getDataTable(list);
    }

// ===================================================================
//  确认
// ===================================================================

    /**
     * 确认物料号首台（确认可生成）
     * <p>将该车辆 generate_affirm 置为 1，记录确认人和时间。
     * <p>确认后该车辆不再出现在 dimension=material 的待确认列表中。
     */
    @Operation(summary = "确认物料号首台（可生成）")
    @RequiresPermissions("vehicle:first:confirm")
    @Log(title = "首台车确认", businessType = BusinessType.UPDATE)
    @PutMapping("/first/material/confirm/{vehicleId}")
    public AjaxResult confirmMaterial(@PathVariable Long vehicleId) {
        firstVehicleCheckService.confirmMaterial(vehicleId, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    /**
     * 确认模版首台（确认可上传）
     * <p>将该车辆 upload_affirm 置为 1，记录确认人和时间。
     * <p>确认后该车辆不再出现在 dimension=template 的待确认列表中。
     */
    @Operation(summary = "确认模版首台（可上传）")
    @RequiresPermissions("vehicle:first:confirm")
    @Log(title = "首台车确认", businessType = BusinessType.UPDATE)
    @PutMapping("/first/template/confirm/{vehicleId}")
    public AjaxResult confirmTemplate(@PathVariable Long vehicleId) {
        firstVehicleCheckService.confirmTemplate(vehicleId, SecurityUtils.getUsername());
        return AjaxResult.success();
    }
}
