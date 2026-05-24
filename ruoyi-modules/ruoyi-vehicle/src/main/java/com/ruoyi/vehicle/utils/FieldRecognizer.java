package com.ruoyi.vehicle.utils;

import java.util.HashMap;
import java.util.Map;

public class FieldRecognizer {

    private static final Map<String, EuFieldType> FIELD_TYPE_MAP = new HashMap<>();

    static {
        FIELD_TYPE_MAP.put("ManufacturerPlaceOfResidence", EuFieldType.ADDRESS);
        FIELD_TYPE_MAP.put("ManufacturerCountryOfResidence", EuFieldType.ADDRESS);
        FIELD_TYPE_MAP.put("IntendedCountryRegistration", EuFieldType.COUNTRY_CODE);
        FIELD_TYPE_MAP.put("TestFamilyIdentifier", EuFieldType.MULTI_VALUE);
        FIELD_TYPE_MAP.put("LocationMarkingsVehiclePart", EuFieldType.ENUM);
        FIELD_TYPE_MAP.put("LocationMarkingsVehiclePartSide", EuFieldType.ENUM);
        FIELD_TYPE_MAP.put("LocationMarkingsSubject", EuFieldType.ENUM);
        FIELD_TYPE_MAP.put("EngineSpeedMaximumNetPowerMax", EuFieldType.POWER_RPM);
        FIELD_TYPE_MAP.put("TyreNumber", EuFieldType.TIRE_SIZE);
        FIELD_TYPE_MAP.put("AxleTrack", EuFieldType.MULTI_VALUE);
        FIELD_TYPE_MAP.put("WltpCo2Low", EuFieldType.DECIMAL);
        FIELD_TYPE_MAP.put("WltpCo2Medium", EuFieldType.DECIMAL);
        FIELD_TYPE_MAP.put("WltpCo2High", EuFieldType.DECIMAL);
        FIELD_TYPE_MAP.put("WltpCo2ExtraHigh", EuFieldType.DECIMAL);
        FIELD_TYPE_MAP.put("WltpCo2Combined", EuFieldType.DECIMAL);
    }

    public static EuFieldType recognize(String dictLabel) {
        if (dictLabel == null) {
            return EuFieldType.UNKNOWN;
        }
        EuFieldType type = FIELD_TYPE_MAP.get(dictLabel);
        return type == null ? EuFieldType.UNKNOWN : type;
    }
}
