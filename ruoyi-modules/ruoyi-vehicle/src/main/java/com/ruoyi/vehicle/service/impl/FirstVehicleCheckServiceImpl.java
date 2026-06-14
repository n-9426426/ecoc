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
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.mapper.VehicleLifecycleMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
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

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Autowired
    private MaterialMapper materialMapper;

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
            // 无论确认状态，被删车辆涉及的物料号都要重算，保证 flag 始终正确
            deletedList.stream()
                    .map(VehicleInfo::getMaterialNo)
                    .filter(this::isNotBlank)
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateMaterialFlag);
        }

        if (isSwitchOn(KEY_NEW_TEMPLATE)) {
            deletedList.stream()
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

        List<String> templateIds = vehicleInfoMapper.findTemplateIdsByUuid(uuid);
        if (templateIds == null || templateIds.isEmpty()) {
            log.debug("[首台车] 模版 uuid={} 无关联车辆，跳过", uuid);
            return;
        }

        // 重置模版确认状态
        templateIds.forEach(templateId -> {
            vehicleInfoMapper.resetTemplateConfirm(templateId);
            log.info("[首台车] 模版修改 uuid={} templateId={} → 重置确认状态", uuid, templateId);
        });
        vehicleTemplateMapper.resetAffirmByUuid(uuid);
        //materialMapper.resetAffirmByTemplateUuid(uuid);

        // 重新打标模版维度
        templateIds.forEach(this::recalculateTemplateFlag);

        // ★ 同步重算关联物料号维度的 flag
        // 因为模版修改了，upload_affirm 被重置，关联的物料号下的车也需要重新出现在首台车列表
        templateIds.forEach(templateId -> {
            List<String> materialNos = vehicleInfoMapper.findMaterialNosByTemplateId(templateId);
            materialNos.stream()
                    .filter(m -> m != null && !m.trim().isEmpty())
                    .collect(Collectors.toSet())
                    .forEach(this::recalculateMaterialFlag);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmMaterial(Long vehicleId, String confirmedBy) {
        vehicleInfoMapper.confirmMaterialFlag(vehicleId, confirmedBy);
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleId);

        int newGenerateAffirm = vehicleInfo.getGenerateAffirm();
        materialMapper.updateGenerateAffirmByMaterialNo(vehicleInfo.getMaterialNo(), newGenerateAffirm);

        // 确认后同步该物料号下所有车辆的 generate_affirm
        vehicleInfoMapper.updateGenerateAffirmByMaterialNo(vehicleInfo.getMaterialNo(), newGenerateAffirm);
        if (vehicleInfo.getVehicleTemplateId() != null) {
            vehicleTemplateMapper.updateGenerateAffirmByTemplateId(
                    Long.parseLong(vehicleInfo.getVehicleTemplateId()), newGenerateAffirm);
        }

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
        if (vehicleInfo.getIssueDate() != null) {
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
        }
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
    public void confirmTemplate(Long vehicleId, String confirmedBy) {
        vehicleInfoMapper.confirmTemplateFlag(vehicleId, confirmedBy);
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleId);

        int newUploadAffirm = vehicleInfo.getUploadAffirm();
        materialMapper.updateUploadAffirmByMaterialNo(vehicleInfo.getMaterialNo(), newUploadAffirm);

        // 确认后同步该模版下所有车辆的 upload_affirm
        vehicleInfoMapper.updateUploadAffirmByTemplateId(vehicleInfo.getVehicleTemplateId(), newUploadAffirm);
        if (vehicleInfo.getVehicleTemplateId() != null) {
            vehicleTemplateMapper.updateUploadAffirmByTemplateId(
                    Long.parseLong(vehicleInfo.getVehicleTemplateId()), newUploadAffirm);
        }

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
        if (vehicleInfo.getIssueDate() != null) {
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
        }
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
     * flag 始终跟随制造日期最早的那辆存活车辆
     */
    private void recalculateMaterialFlag(String materialNo) {
        if (!isNotBlank(materialNo)) {
            return;
        }
        vehicleInfoMapper.clearMaterialFlagByMaterialNo(materialNo);
        Long earliestId = vehicleInfoMapper.findEarliestIdByMaterialNo(materialNo);
        if (earliestId == null) {
            return;
        }
        vehicleInfoMapper.markMaterialFlag(earliestId, 1);
    }

    // ===================================================================
    //  核心计算：模版维度
    // ===================================================================

    /**
     * 重新计算某个模版下应该打标的车辆（逻辑与物料号维度完全对称）。
     *
     * flag 始终跟随制造日期最早的那辆存活车辆
     */
    private void recalculateTemplateFlag(String templateId) {
        log.info("[首台车] recalculateTemplateFlag 开始 templateId={}", templateId);
        vehicleInfoMapper.clearTemplateFlagByTemplateId(templateId);
        Long earliestId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);
        if (earliestId == null) {
            log.info("[首台车] recalculateTemplateFlag templateId={} 无车辆，跳过", templateId);
            return;
        }
        log.info("[首台车] recalculateTemplateFlag templateId={} 打标 vehicleId={}", templateId, earliestId);
        vehicleInfoMapper.markTemplateFlag(earliestId, 1);
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

    public boolean isSwitchOn(String key) {
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
