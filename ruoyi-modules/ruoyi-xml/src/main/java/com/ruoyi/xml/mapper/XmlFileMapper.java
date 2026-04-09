package com.ruoyi.xml.mapper;

import com.ruoyi.xml.domain.XmlFile;

import java.util.List;

/**
 * XML文件Mapper接口
 */
public interface XmlFileMapper {
    /**
     * 查询XML文件列表
     */
    List<XmlFile> selectXmlFileList(XmlFile xmlFile);

    /**
     * 查询XML文件
     */
    XmlFile selectXmlFileById(Long id);

    /**
     * 新增XML文件
     */
    int insertXmlFile(XmlFile xmlFile);

    /**
     * 修改XML文件
     */
    int updateXmlFile(XmlFile xmlFile);

    /**
     * 删除XML文件
     */
    int deleteXmlFileById(Long id);

    /**
     * 批量删除XML文件
     */
    int deleteXmlFileByIds(Long[] ids);

    /**
     * 查询文件版本列表
     */
    List<XmlFile> selectXmlFileVersions(String fileName);

    int restoreXmlByIds(Long[] xmlIds);

    int permanentlyDeleteXmlByIds(Long[] xmlIds);

    int deleteExpiredXml(Long xmlId);

    /**
     * 物理删除超过一个月的逻辑删除数据
     *
     * @return 删除行数
     */
    public int permanentlyDeleteXmlById(Long xmlId);

    String selectVersionByFileName(String fileName);

    void updateIsLatestToFalse(Long id);
}