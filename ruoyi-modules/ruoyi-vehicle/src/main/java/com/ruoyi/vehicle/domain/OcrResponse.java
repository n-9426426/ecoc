package com.ruoyi.vehicle.domain;

import java.util.List;

public class OcrResponse {
    private Integer code;
    private List<OcrResult> data;

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public List<OcrResult> getData() {
        return data;
    }

    public void setData(List<OcrResult> data) {
        this.data = data;
    }
}
