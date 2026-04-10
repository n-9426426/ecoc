package com.ruoyi.xml.mapper;

import com.ruoyi.xml.domain.XmlVersion;

import java.util.List;

/**
 * XML版本历史Mapper接口
 */
public interface XmlVersionMapper {
    /**
     * 查询XML版本历史列表
     */
    List<XmlVersion> selectXmlVersionList(XmlVersion xmlVersion);

    /**
     * 新增XML版本历史
     */
    int insertXmlVersion(XmlVersion xmlVersion);

    /**
     * 查询文件的版本历史
     */
    List<XmlVersion> selectXmlVersionsByFileId(Long fileId);

    void deleteXmlVersionByFileId(Long[] xmlIds);
}