package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;
import java.util.List;

public class VehicleInfo extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long vehicleId;

    private List<Long> vehicleIds;

    @Excel(name = "车架号(VIN)")
    private String vin;

    @Excel(name = "车型")
    private String model;

    @Excel(name = "发动机号")
    private String engineNo;

    @Excel(name = "外观颜色")
    private String exteriorColor;

    @Excel(name = "生产日期", dateFormat = "yyyy/MM/dd")
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date productionDate;

    private String importSource;

    private Integer status;

    /** 是否回收 */
    private Boolean reclaim = false;

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEngineNo() {
        return engineNo;
    }

    public void setEngineNo(String engineNo) {
        this.engineNo = engineNo;
    }

    public String getExteriorColor() {
        return exteriorColor;
    }

    public void setExteriorColor(String exteriorColor) {
        this.exteriorColor = exteriorColor;
    }

    public Date getProductionDate() {
        return productionDate;
    }

    public void setProductionDate(Date productionDate) {
        this.productionDate = productionDate;
    }

    public String getImportSource() {
        return importSource;
    }

    public void setImportSource(String importSource) {
        this.importSource = importSource;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Boolean getReclaim() {
        return reclaim;
    }

    public void setReclaim(Boolean reclaim) {
        this.reclaim = reclaim;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("vehicleId", getVehicleId())
                .append("vin", getVin())
                .append("model", getModel())
                .append("engineNo", getEngineNo())
                .append("exteriorColor", getExteriorColor())
                .append("productionDate", getProductionDate())
                .append("importSource", getImportSource())
                .append("status", getStatus())
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .append("remark", getRemark())
                .append("deleted", getDeleted())
                .toString();
    }

    public List<Long> getVehicleIds() {
        return vehicleIds;
    }

    public void setVehicleIds(List<Long> vehicleIds) {
        this.vehicleIds = vehicleIds;
    }
}