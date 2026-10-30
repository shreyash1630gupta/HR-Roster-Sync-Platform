package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeDataDto {

    private String name;
    private String email;
    private String phone;

    @JsonProperty("manager_id")
    private String managerId;

    @JsonProperty("office_code")
    private String officeCode;

    private String shift;
    private String department;
    private String designation;

    // Transport fields (if accidentally passed by HR, to be ignored)
    @JsonProperty("pickup_point")
    private String pickupPoint;

    @JsonProperty("special_assistance_needs")
    private String specialAssistanceNeeds;

    @JsonProperty("drop_point")
    private String dropPoint;

    @JsonProperty("preferred_cab_type")
    private String preferredCabType;
}

