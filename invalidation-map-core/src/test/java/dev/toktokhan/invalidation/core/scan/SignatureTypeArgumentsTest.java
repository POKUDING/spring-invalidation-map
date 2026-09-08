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

    @Test
    void typeArgumentsOfFieldType_singleTypeArgument_reportsTypeArgument() {
        List<String> arguments = SignatureTypeArguments.typeArgumentsOfFieldType(
            "Ljava/util/List<Ljava/lang/String;>;");

        assertThat(arguments).isEqualTo(List.of("java/lang/String"));
    }

    @Test
    void typeArgumentsOfFieldType_twoTypeArguments_reportsBothInOrder() {
        List<String> arguments = SignatureTypeArguments.typeArgumentsOfFieldType(
            "Ljava/util/Map<Ljava/lang/Long;Lcom/example/Member;>;");

        assertThat(arguments).isEqualTo(List.of("java/lang/Long", "com/example/Member"));
    }

    @Test
    void typeArgumentsOfFieldType_nestedGenericTypeArgument_dropsInnerArgumentsAndKeepsOnlyOuterType() {
        // acceptType() 은 accept() 와 진입점이 다릅니다. Task 2 가 accept() 경로(parse())에서
        // 고친 중첩 제네릭 격리가 acceptType() 경로에서도 적용되는지 확인하는 회귀 테스트입니다.
        // 안쪽 String, Foo 가 섞여 들어오면 결함이 재발한 것입니다.
        List<String> arguments = SignatureTypeArguments.typeArgumentsOfFieldType(
            "Ljava/util/List<Ljava/util/Map<Ljava/lang/String;Lcom/example/Foo;>;>;");

        assertThat(arguments).isEqualTo(List.of("java/util/Map"));
    }

    @Test
    void typeArgumentsOfFieldType_noGenericTypeArgument_returnsEmptyList() {
        List<String> arguments = SignatureTypeArguments.typeArgumentsOfFieldType(null);

        assertThat(arguments).isEmpty();
    }
}
