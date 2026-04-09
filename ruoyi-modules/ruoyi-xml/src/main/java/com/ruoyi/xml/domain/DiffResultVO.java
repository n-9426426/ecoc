package com.ruoyi.xml.domain;

import java.util.List;

public class DiffResultVO {
    /** 旧版本每行数据 */
    private List<DiffLineVO> oldLines;
    /** 新版本每行数据 */
    private List<DiffLineVO> newLines;
    /** 是否完全相同 */
    private Boolean isSame;

    public List<DiffLineVO> getOldLines() {
        return oldLines;
    }

    public void setOldLines(List<DiffLineVO> oldLines) {
        this.oldLines = oldLines;
    }

    public List<DiffLineVO> getNewLines() {
        return newLines;
    }

    public void setNewLines(List<DiffLineVO> newLines) {
        this.newLines = newLines;
    }

    public Boolean getSame() {
        return isSame;
    }

    public void setSame(Boolean same) {
        isSame = same;
    }
}