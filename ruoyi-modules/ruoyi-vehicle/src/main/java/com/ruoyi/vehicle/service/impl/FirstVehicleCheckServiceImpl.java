package com.ruoyi.vehicle.service.impl;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleLifecycle;
import com.ruoyi.vehicle.enums.VehicleLifecycleOperation;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.mapper.VehicleLifecycleMapper;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 首台车确认 Service 实现
 *
 * <p><b>核心概念：</b>
 * <ul>
 *   <li>物料号维度：{@code first_material_flag=1} 标记该物料号下制造日期最早的车，
 *       由人工"确认生成"（{@code generate_affirm=1}）后完成确认。</li>
 *   <li>模版维度：{@code first_template_flag=1} 标记该模版下制造日期最早的车，
 *       由人工"确认上传"（{@code upload_affirm=1}）后完成确认。</li>
 * </ul>
 *
 * <p><b>打标规则（物料号/模版通用）：</b>
 * <pre>
 * 该物料号/模版 在库中是否存在？
 * ├── 不存在 → 直接打标
 * └── 存在
 *     ├── 已有确认记录（generate_affirm/upload_affirm=1）→ 跳过，不打标
 *     └── 无确认记录 → 找制造日期最早的打标
 *         ├── 当前 flag=1 的就是最早的 → 不动
 *         └── 当前 flag=1 的不是最早的 → 清全组，给最早的打标
 * </pre>
 *
 * <p><b>删除规则：</b>
 * <pre>
 * 被删的车 generate_affirm=1（物料号已确认）→ 不触发物料号重算
 * 被删的车 generate_affirm=0（未确认）      → 触发物料号重算
 * 模版维度同理（upload_affirm）
 * </pre>
 *
 * <p><b>模版改动规则：</b>
 * 重置该 uuid 下所有关联车辆的模版确认状态（upload_affirm=0，清除确认人/时间），
 * 然后重新打标。
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

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private VehicleLifecycleMapper vehicleLifecycleMapper;

    // ===================================================================
    //  对外接口实现
    // ===================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAfterInsert(List<VehicleInfo> vehicleList) {
        if (vehicleList == null || vehicleList.isEmpty()) {
            return;
        }
        if (isSwitchOn(KEY_NEW_MATERIAL)) {
            collectMaterialNos(vehicleList)
                    .forEach(this::recalculateMaterialFlag);
        }
        if (isSwitchOn(KEY_NEW_TEMPLATE)) {
            collectTemplateIds(vehicleList)
                    .forEach(this::recalculateTemplateFlag);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAfterUpdate(VehicleInfo newInfo) {
        if (newInfo == null || newInfo.getVehicleId() == null) {
            return;
        }

        // 查更新前的旧数据
        VehicleInfo oldInfo = vehicleInfoMapper.selectVehicleInfoById(newInfo.getVehicleId());
        if (oldInfo == null) {
            // 旧数据不存在，按新增处理
            handleAfterInsert(Collections.singletonList(newInfo));
            return;
        }

        // 物料号维度：物料号发生变化时，旧物料号和新物料号都要重算
        if (isSwitchOn(KEY_NEW_MATERIAL)) {
            String oldMaterial = oldInfo.getMaterialNo();
            String newMaterial = newInfo.getMaterialNo();
            boolean materialChanged = !Objects.equals(oldMaterial, newMaterial);
            if (materialChanged) {
                if (isNotBlank(oldMaterial)) {
                    log.info("[首台车] 物料号变更，重算旧物料号={}", oldMaterial);
                    recalculateMaterialFlag(oldMaterial);
                }
                if (isNotBlank(newMaterial)) {
                    log.info("[首台车] 物料号变更，重算新物料号={}", newMaterial);
                    recalculateMaterialFlag(newMaterial);
                }
            }
        }

        // 模版维度：模版 ID 发生变化时，旧模版和新模版都要重算
        if (isSwitchOn(KEY_NEW_TEMPLATE)) {
            String oldTemplateId = oldInfo.getVehicleTemplateId();
            String newTemplateId = newInfo.getVehicleTemplateId();
            boolean templateChanged = !Objects.equals(oldTemplateId, newTemplateId);
            if (templateChanged) {
                if (isNotBlank(oldTemplateId)) {
                    log.info("[首台车] 模版变更，重算旧模版={}", oldTemplateId);
                    recalculateTemplateFlag(oldTemplateId);
                }
                if (isNotBlank(newTemplateId)) {
                    log.info("[首台车] 模版变更，重算新模版={}", newTemplateId);
                    recalculateTemplateFlag(newTemplateId);
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAfterDelete(List<VehicleInfo> deletedList) {
        if (deletedList == null || deletedList.isEmpty()) {
            return;
        }

        if (isSwitchOn(KEY_NEW_MATERIAL)) {
            // 只对"未确认"的车辆涉及的物料号重算
            // 已确认（generate_affirm=1）说明该物料号已走完流程，删除不影响
            deletedList.stream()
                    .filter(v -> !Objects.equals(v.getGenerateAffirm(), 1))
                    .map(VehicleInfo::getMaterialNo)
                    .filter(this::isNotBlank)
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateMaterialFlag);
        }

        if (isSwitchOn(KEY_NEW_TEMPLATE)) {
            // 只对"未确认"的车辆涉及的模版重算
            deletedList.stream()
                    .filter(v -> !Objects.equals(v.getUploadAffirm(), 1))
                    .map(VehicleInfo::getVehicleTemplateId)
                    .filter(this::isNotBlank)
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
        if (uuid == null || uuid.isEmpty()) {
            return;
        }

        // 查出该 uuid 下所有关联的 templateId
        List<String> templateIds = vehicleInfoMapper.findTemplateIdsByUuid(uuid);
        if (templateIds == null || templateIds.isEmpty()) {
            log.debug("[首台车] 模版 uuid={} 无关联车辆，跳过", uuid);
            return;
        }

        // 重置该 uuid 下所有关联车辆的模版确认状态
        // 模版内容改了，之前的"可上传"确认作废，需要重新人工确认
        templateIds.forEach(templateId -> {
            vehicleInfoMapper.resetTemplateConfirm(templateId);
            log.info("[首台车] 模版修改 uuid={} templateId={} → 重置确认状态", uuid, templateId);
        });

        // 重置后，existsConfirmedTemplate 返回 false，recalculateTemplateFlag 可以正常打标
        templateIds.forEach(this::recalculateTemplateFlag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmMaterial(Long vehicleId, String confirmedBy, String generateAffirmCause) {
        vehicleInfoMapper.confirmMaterialFlag(vehicleId, confirmedBy, generateAffirmCause);
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleId);

        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate(VehicleLifecycleOperation.FIRST_VEHICLE_AFFIRM.getOperation());
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
        params.put("vin", vehicleInfo.getVin());
        params.put("vehicleModel", vehicleInfo.getVehicleModel());
        params.put("factoryCode", vehicleInfo.getFactoryCode());
        params.put("country", vehicleInfo.getCountry());
        params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
        params.put("materialNo", vehicleInfo.getMaterialNo());
        params.put("wvtaNo", vehicleInfo.getWvtaNo());
        params.put("cocTemplateNo", vehicleInfo.getCocTemplateNo());
        SysNotice sysNotice = new SysNotice();
        sysNotice.setModel(SysNoticeModel.VEHICLE_TEMPLATE.getModel());
        sysNotice.setQueryParams(JSON.toJSONString(params));
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆生成确认通知");
        sysNotice.setNoticeContent("物料号为 " + vehicleInfo.getMaterialNo() + " 的物料号生成已确认，现已可以生成XML文件");
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(18, 19));
        remoteNoticeService.innerAdd(sysNotice);
        log.info("[首台车] 物料号确认（可生成）vehicleId={} by={}", vehicleId, confirmedBy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmTemplate(Long vehicleId, String confirmedBy, String uploadAffirmCause) {
        vehicleInfoMapper.confirmTemplateFlag(vehicleId, confirmedBy, uploadAffirmCause);
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleId);

        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate(VehicleLifecycleOperation.FIRST_VEHICLE_AFFIRM.getOperation());
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
        params.put("vin", vehicleInfo.getVin());
        params.put("vehicleModel", vehicleInfo.getVehicleModel());
        params.put("factoryCode", vehicleInfo.getFactoryCode());
        params.put("country", vehicleInfo.getCountry());
        params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
        params.put("materialNo", vehicleInfo.getMaterialNo());
        params.put("wvtaNo", vehicleInfo.getWvtaNo());
        params.put("cocTemplateNo", vehicleInfo.getCocTemplateNo());
        SysNotice sysNotice = new SysNotice();
        sysNotice.setModel(SysNoticeModel.VEHICLE_TEMPLATE.getModel());
        sysNotice.setQueryParams(JSON.toJSONString(params));
        sysNotice.setIsRead(false);
        sysNotice.setStatus("0");
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆上传确认通知");
        sysNotice.setNoticeContent("WVTA编号为 " + vehicleInfo.getWvtaNo() + "、COC模版号为 " +vehicleInfo.getCocTemplateNo() + " 的车辆模版上传已确认，现已可以上传XML文件");
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        sysNotice.setSorts(Arrays.asList(20, 21));
        remoteNoticeService.innerAdd(sysNotice);
        log.info("[首台车] 模版确认（可上传）vehicleId={} by={}", vehicleId, confirmedBy);
    }

    // ===================================================================
    //  核心计算：物料号维度
    // ===================================================================

    /**
     * 重新计算某个物料号下应该打标的车辆。
     *
     * <pre>
     * 1. 该物料号已有确认记录（generate_affirm=1）→ 跳过
     * 2. 先清掉该物料号下所有 flag，防止脏数据
     * 3. 找制造日期最早的车
     * 4. 不存在 → 不打标（该物料号下已无车辆）
     * 5. 存在   → 打标
     * </pre>
     */
    private void recalculateMaterialFlag(String materialNo) {
        if (!isNotBlank(materialNo)) {
            return;
        }

        // 已有确认记录，说明该物料号已走完流程，不再重复打标
        if (vehicleInfoMapper.existsConfirmedMaterial(materialNo)) {
            log.debug("[首台车] 物料号={} 已有确认记录，跳过打标", materialNo);
            return;
        }

        // 先清掉该物料号下全部 flag，保证只有一条 flag=1
        vehicleInfoMapper.clearMaterialFlagByMaterialNo(materialNo);

        // 找制造日期最早的
        Long earliestId = vehicleInfoMapper.findEarliestIdByMaterialNo(materialNo);
        if (earliestId == null) {
            log.debug("[首台车] 物料号={} 已无车辆，不打标", materialNo);
            return;
        }

        vehicleInfoMapper.markMaterialFlag(earliestId, 1);
        log.info("[首台车] 物料号={} → 打标 vehicleId={}", materialNo, earliestId);
    }

    // ===================================================================
    //  核心计算：模版维度
    // ===================================================================

    /**
     * 重新计算某个模版下应该打标的车辆（逻辑与物料号维度完全对称）。
     *
     * <pre>
     * 1. 该模版已有确认记录（upload_affirm=1）→ 跳过
     * 2. 先清掉该模版下所有 flag
     * 3. 找制造日期最早的车
     * 4. 不存在 → 不打标
     * 5. 存在   → 打标
     * </pre>
     */
    private void recalculateTemplateFlag(String templateId) {
        if (!isNotBlank(templateId)) {
            return;
        }

        if (vehicleInfoMapper.existsConfirmedTemplate(templateId)) {
            log.debug("[首台车] 模版={} 已有确认记录，跳过打标", templateId);
            return;
        }

        vehicleInfoMapper.clearTemplateFlagByTemplateId(templateId);

        Long earliestId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);
        if (earliestId == null) {
            log.debug("[首台车] 模版={} 已无车辆，不打标", templateId);
            return;
        }

        vehicleInfoMapper.markTemplateFlag(earliestId, 1);
        log.info("[首台车] 模版={} → 打标 vehicleId={}", templateId, earliestId);
    }

    // ===================================================================
    //  工具方法
    // ===================================================================

    private Set<String> collectMaterialNos(List<VehicleInfo> list) {
        return list.stream()
                .map(VehicleInfo::getMaterialNo)
                .filter(this::isNotBlank)
                .collect(Collectors.toSet());
    }

    private Set<String> collectTemplateIds(List<VehicleInfo> list) {
        return list.stream()
                .map(VehicleInfo::getVehicleTemplateId)
                .filter(this::isNotBlank)
                .collect(Collectors.toSet());
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ===================================================================
    //  字典开关
    // ===================================================================

    private boolean isSwitchOn(String key) {
        try {
            R<List<SysDictData>> result = remoteDictService.getDictDataByType(DICT_TYPE);
            if (result == null || !R.isSuccess(result) || result.getData() == null) {
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