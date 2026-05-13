package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.domain.SysAccountConfigQuery;
import com.ruoyi.vehicle.domain.SysHeartbeatLog;
import com.ruoyi.vehicle.domain.SysHeartbeatLogQuery;
import com.ruoyi.vehicle.domain.dto.AccountConfigSaveDTO;
import com.ruoyi.vehicle.service.HeartbeatService;
import com.ruoyi.vehicle.service.SysAccountConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 账号管理 Controller
 */
@Tag(name = "账号管理", description = "各成员国对接账号的增删改查及心跳日志查询")
@RestController
@RequestMapping("/account/config")
@RequiredArgsConstructor
public class SysAccountConfigController extends BaseController {

    private final SysAccountConfigService accountConfigService;
    private final HeartbeatService heartbeatService;

    /**
     * 账号列表（分页）
     */
    @Operation(summary = "账号列表", description = "支持按国家、心跳状态、启用状态多条件分页查询")
    @RequiresPermissions("account:config:list")
    @GetMapping("/list")
    public TableDataInfo list(SysAccountConfigQuery query) {
        startPage();
        List<SysAccountConfig> list = accountConfigService.selectList(query);
        return getDataTable(list);
    }

    /**
     * 账号详情
     */
    @Operation(summary = "账号详情", description = "按主键查询账号配置详情，密码字段脱敏返回")
    @Parameter(name = "id", description = "账号主键ID", required = true)
    @RequiresPermissions("account:config:query")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return AjaxResult.success(accountConfigService.selectById(id));
    }

    /**
     * 新增账号
     */
    @Operation(summary = "新增账号", description = "新建国家对接账号配置，密码加密存储")
    @RequiresPermissions("account:config:add")
    @PostMapping
    public AjaxResult add(@RequestBody @Validated AccountConfigSaveDTO dto) {
        return toAjax(accountConfigService.insert(dto));
    }

    /**
     * 编辑账号
     */
    @Operation(summary = "编辑账号", description = "修改账号配置，id必传")
    @RequiresPermissions("account:config:edit")
    @PutMapping
    public AjaxResult edit(@RequestBody @Validated AccountConfigSaveDTO dto) {
        return toAjax(accountConfigService.update(dto));
    }

    /**
     * 删除账号
     */
    @Operation(summary = "删除账号", description = "逻辑删除，删除后该国接口停止匹配，XML无法再向该国发起审批上传")
    @Parameter(name = "id", description = "账号主键ID", required = true)
    @RequiresPermissions("account:config:remove")
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id) {
        return toAjax(accountConfigService.deleteById(id));
    }

    /**
     * 心跳日志列表（分页）
     */
    @Operation(summary = "心跳日志列表", description = "支持按国家、检测结果、时间范围分页查询，日志保留90天")
    @RequiresPermissions("account:config:list")
    @GetMapping("/heartbeat/logs")
    public TableDataInfo heartbeatLogs(SysHeartbeatLogQuery query) {
        startPage();
        List<SysHeartbeatLog> list = heartbeatService.selectLogList(query);
        return getDataTable(list);
    }
}
