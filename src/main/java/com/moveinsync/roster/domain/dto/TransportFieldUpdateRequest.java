package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransportFieldUpdateRequest {

    @JsonProperty("pickup_point")
    private String pickupPoint;

    @JsonProperty("drop_point")
    private String dropPoint;

    @JsonProperty("special_assistance_needs")
    private String specialAssistanceNeeds;

    @JsonProperty("preferred_cab_type")
    private String preferredCabType;
}

