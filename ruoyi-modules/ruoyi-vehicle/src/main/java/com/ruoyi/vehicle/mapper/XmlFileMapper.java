package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.XmlFile;
import org.apache.ibatis.annotations.Param;

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

    List<String> selectFilePathsByIds(Long[] xmlIds);

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

    void updateIsLatestToFalse(String fileName);

    List<XmlFile> checkXmlFileTimeoutUpload(@Param("noticeStatus") Integer noticeStatus);

    void updateXmlFileTimeoutUpload(@Param("xmlFileIds") List<Long> xmlFileIds, @Param("status") Integer status);

    List<XmlFile> selectXmlFileByIds(@Param("xmlIds") Long[] xmlIds);

    /**
     * 将指定 XML 记录标记为曾经强制上传（force_uploaded = 1）
     */
    int updateForceUploaded(Long id);


    // XmlFileMapper.java 接口新增
    /**
     * 批量查询哪些VIN已存在最新版本的XML记录（is_latest=1 AND deleted=0）
     * @param vinList VIN列表
     * @return 已生成过XML的VIN集合
     */
    List<String> selectVinsWithGeneratedXml(@Param("vinList") List<String> vinList);
}
