package com.ruoyi.vehicle.service.impl;

import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 首台车确认 Service 实现
 *
 * <p>核心计算逻辑（物料号和模版各自独立）：
 * <pre>
 * 同一物料号/模版下：
 *   1. 存在 flag=1 且未确认的车：
 *      - 它就是制造日期最早的 → 不动
 *      - 它不是最早的         → 清掉它，把最早的打上
 *   2. 不存在任何 flag=1 的车（全部未打标或已确认）：
 *      - 存在已确认的车       → 说明已经处理过，不再打新标
 *      - 不存在已确认的车     → 找最早的打上
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirstVehicleCheckServiceImpl implements IFirstVehicleCheckService {

    private static final String DICT_TYPE             = "first_vehicle_switch";
    private static final String KEY_NEW_MATERIAL      = "new_material";
    private static final String KEY_NEW_TEMPLATE      = "new_template";
    private static final String KEY_TEMPLATE_MODIFIED = "template_modified";

    private final VehicleInfoMapper vehicleInfoMapper;
    private final RemoteDictService remoteDictService;

    // ===================================================================
    //  对外接口实现
    // ===================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAfterInsert(List<VehicleInfo> vehicleList) {
        if (vehicleList == null || vehicleList.isEmpty()) {
            return;
        }
        // 场景1：提取本次涉及的所有物料号（去重），逐个重新计算
        if (isSwitchOn(KEY_NEW_MATERIAL)) {
            vehicleList.stream()
                    .map(VehicleInfo::getMaterialNo)
                    .filter(s -> s != null && !s.isEmpty())
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateMaterialFlag);
        }
        // 场景2：提取本次涉及的所有模版ID（去重），逐个重新计算
        if (isSwitchOn(KEY_NEW_TEMPLATE)) {
            vehicleList.stream()
                    .map(VehicleInfo::getVehicleTemplateId)
                    .filter(s -> s != null && !s.isEmpty())
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateTemplateFlag);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAfterDelete(List<VehicleInfo> deletedList) {
        if (deletedList == null || deletedList.isEmpty()) {
            return;
        }
        // 只对删除前持有标识的车辆涉及的物料号/模版重新计算
        if (isSwitchOn(KEY_NEW_MATERIAL)) {
            deletedList.stream()
                    .filter(v -> Objects.equals(v.getFirstMaterialFlag(), 1))
                    .map(VehicleInfo::getMaterialNo)
                    .filter(s -> s != null && !s.isEmpty())
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateMaterialFlag);
        }
        if (isSwitchOn(KEY_NEW_TEMPLATE)) {
            deletedList.stream()
                    .filter(v -> Objects.equals(v.getFirstTemplateFlag(), 1))
                    .map(VehicleInfo::getVehicleTemplateId)
                    .filter(s -> s != null && !s.isEmpty())
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateTemplateFlag);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAfterTemplateModified(String uuid) {
        if (!isSwitchOn(KEY_TEMPLATE_MODIFIED)) {
            return;
        }
        // 通过 uuid 找该模版组下所有 template_id 关联的车辆，逐个重新计算
        List<String> templateIds = vehicleInfoMapper.findTemplateIdsByUuid(uuid);
        if (templateIds == null || templateIds.isEmpty()) {
            return;
        }
        // 清除所有关联车辆的模版标识
        templateIds.forEach(vehicleInfoMapper::clearTemplateFlagByTemplateId);
        // 每个 templateId 下重新找最早的打标（因为不同 templateId 下的车辆是独立的）
        templateIds.forEach(templateId -> {
            Long earliestId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);
            if (earliestId != null) {
                vehicleInfoMapper.markTemplateFlag(earliestId, 1);
                log.info("[首台车] 模版修改 uuid={} templateId={} → 重新打标 vehicleId={}", uuid, templateId, earliestId);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmMaterial(Long vehicleId, String confirmedBy) {
        vehicleInfoMapper.confirmMaterialFlag(vehicleId, confirmedBy);  // 更新 generate_affirm=1 + confirmed 字段
        log.info("[首台车] 物料号确认 vehicleId={} by={}", vehicleId, confirmedBy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmTemplate(Long vehicleId, String confirmedBy) {
        vehicleInfoMapper.confirmTemplateFlag(vehicleId, confirmedBy);  // 更新 upload_affirm=1 + confirmed 字段
        log.info("[首台车] 模版确认 vehicleId={} by={}", vehicleId, confirmedBy);
    }

    @Override
    public List<VehicleInfo> listFirstMaterialUnconfirmed() {
        return vehicleInfoMapper.listFirstMaterialUnconfirmed();
    }

    @Override
    public List<VehicleInfo> listFirstTemplateUnconfirmed() {
        return vehicleInfoMapper.listFirstTemplateUnconfirmed();
    }

    // ===================================================================
    //  核心计算：物料号维度
    // ===================================================================

    /**
     * 重新计算某个物料号下应该打标的车辆
     *
     * <pre>
     * 1. 查出当前 flag=1 且未确认的车（pending）
     * 2. 查出该物料号下制造日期最早的车（earliest）
     * 3. 如果已有已确认的记录 → 不打新标，直接返回
     * 4. pending 和 earliest 是同一辆 → 不动
     * 5. pending 不是 earliest       → 清掉 pending，给 earliest 打标
     * 6. 没有 pending                → 给 earliest 打标
     * </pre>
     */
    private void recalculateMaterialFlag(String materialNo) {
        // 是否存在已确认的记录
        boolean hasConfirmed = vehicleInfoMapper.existsConfirmedMaterial(materialNo);
        if (hasConfirmed) {
            log.debug("[首台车] 物料号 {} 已有确认记录，跳过打标", materialNo);
            return;
        }

        // 当前待确认（flag=1）的 vehicle_id
        Long pendingId = vehicleInfoMapper.findPendingMaterialFlagId(materialNo);

        // 制造日期最早的 vehicle_id
        Long earliestId = vehicleInfoMapper.findEarliestIdByMaterialNo(materialNo);

        if (earliestId == null) {
            return;
        }

        if (pendingId != null && pendingId.equals(earliestId)) {
            // 标识正确，不动
            log.debug("[首台车] 物料号 {} 标识正确，vehicleId={}", materialNo, pendingId);
            return;
        }

        if (pendingId != null) {
            // 标识打在了错误的车上，先清掉
            vehicleInfoMapper.markMaterialFlag(pendingId, 0);
            log.info("[首台车] 物料号 {} 标识转移 {} → {}", materialNo, pendingId, earliestId);
        }

        vehicleInfoMapper.markMaterialFlag(earliestId, 1);
        log.info("[首台车] 物料号 {} → 打标 vehicleId={}", materialNo, earliestId);
    }

    // ===================================================================
    //  核心计算：模版维度
    // ===================================================================

    /**
     * 重新计算某个模版下应该打标的车辆（逻辑同物料号维度）
     */
    private void recalculateTemplateFlag(String templateId) {
        boolean hasConfirmed = vehicleInfoMapper.existsConfirmedTemplate(templateId);
        if (hasConfirmed) {
            log.debug("[首台车] 模版 {} 已有确认记录，跳过打标", templateId);
            return;
        }

        Long pendingId  = vehicleInfoMapper.findPendingTemplateFlagId(templateId);
        Long earliestId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);

        if (earliestId == null) {
            return;
        }

        if (pendingId != null && pendingId.equals(earliestId)) {
            log.debug("[首台车] 模版 {} 标识正确，vehicleId={}", templateId, pendingId);
            return;
        }

        if (pendingId != null) {
            vehicleInfoMapper.markTemplateFlag(pendingId, 0);
            log.info("[首台车] 模版 {} 标识转移 {} → {}", templateId, pendingId, earliestId);
        }

        vehicleInfoMapper.markTemplateFlag(earliestId, 1);
        log.info("[首台车] 模版 {} → 打标 vehicleId={}", templateId, earliestId);
    }

    // ===================================================================
    //  字典开关
    // ===================================================================

    private boolean isSwitchOn(String key) {
        try {
            R<List<SysDictData>> result = remoteDictService.getDictDataByType(DICT_TYPE);
            if (result == null ||!R.isSuccess(result) || result.getData() == null) {
                return false;
            }
            return result.getData().stream()
                    .filter(d -> key.equals(d.getDictLabel()))
                    .findFirst()
                    .map(d -> "1".equals(d.getDictValue()))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[首台车] 读取字典开关异常 key={}", key, e);
            return false;
        }
    }
}
