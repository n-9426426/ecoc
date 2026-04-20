package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.XmlTemplate;
import com.ruoyi.vehicle.domain.XmlTemplateAttribute;
import com.ruoyi.vehicle.domain.vo.AttributeTreeNode;
import com.ruoyi.vehicle.domain.vo.XmlTemplateVo;
import com.ruoyi.vehicle.mapper.XmlTemplateAttributeMapper;
import com.ruoyi.vehicle.mapper.XmlTemplateMapper;
import com.ruoyi.vehicle.service.IXmlTemplateService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class XmlTemplateServiceImpl implements IXmlTemplateService {

    @Autowired
    private XmlTemplateMapper templateMapper;

    @Autowired
    private XmlTemplateAttributeMapper attributeMapper;

    @Autowired
    private RemoteDictService remoteDictService;

    // ==================== 查询列表 ====================

    @Override
    public List<XmlTemplateVo> selectTemplateList(XmlTemplate query) {
        // 1. 查询模板列表（XML中过滤 deleted=0）
        List<XmlTemplate> templateList = templateMapper.selectTemplateList(query);
        return getXmlTemplateVos(templateList);
    }

    // ==================== 查询详情（含属性树） ====================

    @Override
    public XmlTemplateVo selectTemplateDetail(Long templateId) {
        // 1. 查询模板（XML中过滤 deleted=0）
        XmlTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new ServiceException("模板不存在");
        }

        // 2. 查询该模板的属性路径
        List<XmlTemplateAttribute> attrList = attributeMapper.selectByTemplateId(templateId);

        // 3. 查询字典
        Map<Long, SysDictData> attrDictMap = getDictMap("vehicle_attribute");
        Map<Long, SysDictData> modelDictMap = getDictMap("vehicle_model");

        // 4. 组装VO
        XmlTemplateVo vo = new XmlTemplateVo();
        BeanUtils.copyProperties(template, vo);

        SysDictData modelDict = modelDictMap.get(template.getModelDictCode());
        vo.setModelDictLabel(modelDict != null ? modelDict.getDictLabel() : "");
        vo.setAttributeTree(buildAttributeTree(attrList, attrDictMap));

        return vo;
    }

    // ==================== 新增 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertTemplate(XmlTemplate template) {
        template.setTemplateCode(UUID.randomUUID().toString().replace("-", ""));
        template.setDeleted(0);
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(new Date());
        templateMapper.insert(template);
        if (template.getAttributeTree() != null && !template.getAttributeTree().isEmpty()) {
            saveAttributeTree(template.getTemplateId(), template.getAttributeTree());
        }

        return 1;
    }

    // ==================== 修改 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTemplate(XmlTemplate template) {
        // 1. 校验模板是否存在
        XmlTemplate dbTemplate = templateMapper.selectById(template.getTemplateId());
        if (dbTemplate == null) {
            throw new ServiceException("模板不存在");
        }

        // 2. 更新主表
        template.setUpdateBy(SecurityUtils.getUsername());
        template.setUpdateTime(new Date());
        templateMapper.updateById(template);

        // 3. 逻辑删除旧属性路径
        attributeMapper.deleteByTemplateIds(Collections.singletonList(template.getTemplateId()));

        // 4. 保存新属性树（全量替换）
        if (template.getAttributeTree() != null && !template.getAttributeTree().isEmpty()) {
            saveAttributeTree(template.getTemplateId(), template.getAttributeTree());
        }

        return 1;
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTemplates(List<Long> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            throw new ServiceException("请选择要删除的模板");
        }

        // 1. 校验所有模板是否存在（过滤已删除的）
        XmlTemplate query = new XmlTemplate();
        List<XmlTemplate> existList = templateMapper.selectTemplateList(query);
        Set<Long> existIds = existList.stream()
                .map(XmlTemplate::getTemplateId)
                .collect(Collectors.toSet());

        List<Long> invalidIds = templateIds.stream()
                .filter(id -> !existIds.contains(id))
                .collect(Collectors.toList());
        if (!invalidIds.isEmpty()) {
            throw new ServiceException("以下模板不存在或已删除：" + invalidIds);
        }

        // 2. 批量逻辑删除属性路径
        attributeMapper.deleteByTemplateIds(templateIds);

        // 3. 批量逻辑删除模板主表
        return templateMapper.deleteByIds(templateIds);
    }

    @Override
    public List<XmlTemplateVo> selectTemplateAll() {
        // 1. 查询模板列表（XML中过滤 deleted=0）
        List<XmlTemplate> templateList = templateMapper.selectTemplateAll();
        return getXmlTemplateVos(templateList);
    }

    private List<XmlTemplateVo> getXmlTemplateVos(List<XmlTemplate> templateList) {
        if (templateList == null || templateList.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 提取所有模板ID
        List<Long> templateIds = templateList.stream()
                .map(XmlTemplate::getTemplateId)
                .collect(Collectors.toList());

        // 3. 批量查询属性路径（避免N+1）
        List<XmlTemplateAttribute> allAttrList = attributeMapper.selectByTemplateIds(templateIds);

        // 4. 按模板ID分组
        Map<Long, List<XmlTemplateAttribute>> attrGroupMap = allAttrList.stream()
                .collect(Collectors.groupingBy(XmlTemplateAttribute::getTemplateId));

        // 5. 一次性查询字典，所有模板共用
        Map<Long, SysDictData> attrDictMap = getDictMap("vehicle_attribute");
        Map<Long, SysDictData> modelDictMap = getDictMap("vehicle_model");

        // 6. 组装VO（每个模板均携带属性树）
        return templateList.stream().map(template -> {
            XmlTemplateVo vo = new XmlTemplateVo();
            BeanUtils.copyProperties(template, vo);

            SysDictData modelDict = modelDictMap.get(template.getModelDictCode());
            vo.setModelDictLabel(modelDict != null ? modelDict.getDictLabel() : "");

            List<XmlTemplateAttribute> attrList = attrGroupMap
                    .getOrDefault(template.getTemplateId(), Collections.emptyList());
            vo.setAttributeTree(buildAttributeTree(attrList, attrDictMap));

            return vo;
        }).collect(Collectors.toList());
    }

        // ==================== 构建属性树 ====================

    /**
     * 构建属性树
     *
     * 核心思路：
     *  - attr_path 使用 "." 分隔，每段为 dict_code，代表层级关系
     *    例：201.202 表示 201 为父节点，202 为其子节点
     *  - 先按路径深度（段数）升序排列，保证父节点先于子节点处理
     *  - 用 nodeMap（path → node）缓存已创建节点，O(1) 快速找到父节点
     *  - 路径只有1段 → 根节点；否则截取父路径，从 nodeMap 取父节点并挂载子节点
     */
    private List<AttributeTreeNode> buildAttributeTree(List<XmlTemplateAttribute> attrList, Map<Long, SysDictData> attrDictMap) {
        if (attrList == null || attrList.isEmpty()) {
            return Collections.emptyList();
        }

        // 按路径深度升序，确保父节点先被处理
        attrList.sort(Comparator.comparingInt(r -> r.getAttrPath().split("\\.").length));

        // path → node 映射，用于快速找父节点
        Map<String, AttributeTreeNode> nodeMap = new LinkedHashMap<>();
        List<AttributeTreeNode> rootNodes = new ArrayList<>();

        for (XmlTemplateAttribute attr : attrList) {
            String path = attr.getAttrPath();
            String[] parts = path.split("\\.");

            // 取路径最后一段作为当前节点的 dict_code
            Long currentDictCode = Long.parseLong(parts[parts.length - 1]);
            SysDictData dictData = attrDictMap.get(currentDictCode);
            if (dictData == null) {
                // 字典中不存在该属性，属脏数据，跳过
                continue;
            }

            // 构建节点
            AttributeTreeNode node = new AttributeTreeNode();
            node.setDictCode(currentDictCode);
            node.setDictLabel(dictData.getDictLabel());
            node.setAttrPath(path);
            node.setDefaultValue(attr.getDefaultValue());
            node.setIsRequired(attr.getIsRequired());
            node.setIsEditable(attr.getIsEditable());
            node.setSortOrder(attr.getSortOrder());
            node.setChildren(new ArrayList<>());

            nodeMap.put(path, node);

            if (parts.length == 1) {
                // 根节点
                rootNodes.add(node);
            } else {
                // 截取父路径，从 nodeMap 找父节点并挂载
                String parentPath = path.substring(0, path.lastIndexOf('.'));
                AttributeTreeNode parentNode = nodeMap.get(parentPath);
                if (parentNode != null) {
                    parentNode.getChildren().add(node);
                }
                // parentNode 为 null 说明父路径未记录（脏数据），跳过挂载
            }
        }

        return rootNodes;
    }

    // ==================== 保存属性树（递归） ====================

    private void saveAttributeTree(Long templateId, List<AttributeTreeNode> tree) {
        saveTreeNodes(templateId, tree, "", SecurityUtils.getUsername());
    }

    private void saveTreeNodes(Long templateId, List<AttributeTreeNode> nodes,
                               String parentPath, String createBy) {
        for (int i = 0; i < nodes.size(); i++) {
            AttributeTreeNode node = nodes.get(i);

            // 构建当前完整路径
            String currentPath = parentPath.isEmpty()
                    ? String.valueOf(node.getDictCode())
                    : parentPath + "." + node.getDictCode();

            XmlTemplateAttribute attr = new XmlTemplateAttribute();
            attr.setTemplateId(templateId);
            attr.setAttrPath(currentPath);
            attr.setDefaultValue(node.getDefaultValue());
            attr.setIsRequired(node.getIsRequired() != null ? node.getIsRequired() : 0);
            attr.setIsEditable(node.getIsEditable() != null ? node.getIsEditable() : 1);
            attr.setSortOrder(i + 1);
            attr.setDeleted(0);
            attr.setCreateBy(createBy);
            attr.setCreateTime(new Date());
            attributeMapper.insert(attr);

            // 递归处理子节点
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                saveTreeNodes(templateId, node.getChildren(), currentPath, createBy);
            }
        }
    }

    // ==================== 工具：字典映射 ====================

    private Map<Long, SysDictData> getDictMap(String dictType) {
        List<SysDictData> list = remoteDictService.getDictDataByType(dictType).getData();
        return list.stream().collect(Collectors.toMap(SysDictData::getDictCode, d -> d));
    }
}