package io.kanon.specctl.core.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionTypeNamesTest {
    @Test
    void stripsPackagesAndTypeUseAnnotations() {
        assertThat(ExtractionTypeNames.canonicalize(
                "java.util.List<@jakarta.validation.constraints.NotNull(message = \"x\") java.util.UUID>"
        )).isEqualTo("List<UUID>");
    }

    @Test
    void stripsSpoonStyleAnnotatedElementTypes() {
        assertThat(ExtractionTypeNames.canonicalize(
                "java.util.List<java.lang.@jakarta.validation.constraints.Pattern(regexp = \"[A-Z]{2,3}\", message = \"x\") String>"
        )).isEqualTo("List<String>");
    }

    @Test
    void keepsWildcardBoundsWhileNormalizingNames() {
        assertThat(ExtractionTypeNames.canonicalize(
                "java.util.Collection<? extends org.springframework.security.core.GrantedAuthority>"
        )).isEqualTo("Collection<? extends GrantedAuthority>");
    }
}
