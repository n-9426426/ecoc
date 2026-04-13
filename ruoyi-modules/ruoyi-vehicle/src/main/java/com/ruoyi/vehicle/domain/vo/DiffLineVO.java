package com.ruoyi.vehicle.domain.vo;

public class DiffLineVO {
    /** 行号 */
    private Integer lineNumber;
    /** 行内容 */
    private String content;
    /** 行类型：normal-相同| removed-删除(旧) | added-新增(新) | empty-空行占位 */
    private String type;

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}