package dev.toktokhan.invalidation.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BytesTest {

    @Test
    void of_bootstrapLoadedClass_fallsBackToOwnClassLoader() {
        assertThat(Bytes.of(String.class)).isNotEmpty();
    }
}
