package io.kanon.fixture;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

public class AnnotatedCollectionDto {
    private List<@Pattern(regexp = "[A-Z]{2,3}", message = "Region code must contain 2-3 uppercase letters") String>
            regionCodes;

    private List<@NotNull(message = "Organization ID must not be null") UUID> organizationIds;
}
