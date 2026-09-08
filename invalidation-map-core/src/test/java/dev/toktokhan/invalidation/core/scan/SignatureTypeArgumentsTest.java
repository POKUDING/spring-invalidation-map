package dev.toktokhan.invalidation.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureTypeArgumentsTest {

    @Test
    void parse_singleTypeArgument_reportsTypeArgument() {
        Map<String, List<String>> arguments = SignatureTypeArguments.parse(
            "Lcom/querydsl/core/types/dsl/EntityPathBase<Lcom/example/Run;>;");

        assertThat(arguments)
            .containsEntry("com/querydsl/core/types/dsl/EntityPathBase", List.of("com/example/Run"));
    }

    @Test
    void parse_typeArgumentIsItselfGeneric_dropsNestedArgumentsAndKeepsOnlyOuterType() {
        Map<String, List<String>> arguments = SignatureTypeArguments.parse(
            "Ljava/util/ArrayList<Ljava/util/Map<Ljava/lang/String;Lcom/example/Foo;>;>;");

        assertThat(arguments).containsEntry("java/util/ArrayList", List.of("java/util/Map"));
    }

    @Test
    void parse_genericTypeArgumentFollowedByPlainArgument_keepsArgumentOrder() {
        Map<String, List<String>> arguments = SignatureTypeArguments.parse(
            "Ldev/toktokhan/Foo<Ljava/util/Map<Ljava/lang/String;Lcom/example/Bar;>;Lcom/example/Baz;>;");

        assertThat(arguments)
            .containsEntry("dev/toktokhan/Foo", List.of("java/util/Map", "com/example/Baz"));
    }
}
