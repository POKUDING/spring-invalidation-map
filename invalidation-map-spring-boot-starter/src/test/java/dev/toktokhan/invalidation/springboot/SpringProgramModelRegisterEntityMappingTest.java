package dev.toktokhan.invalidation.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SpringProgramModel#registerEntityMapping} 단위 테스트입니다. Spring 컨텍스트 없이
 * 순수 로직만 봅니다 — 두 리포지토리가 같은 프래그먼트 인터페이스를 서로 다른 엔티티로
 * 등록하는 충돌은 픽스처 애플리케이션에 리포지토리를 추가로 심지 않고 이 방법으로 더
 * 정확하게 확인할 수 있습니다.
 */
class SpringProgramModelRegisterEntityMappingTest {

    @Test
    void newInterface_isAdded() {
        Map<String, String> byInterface = new LinkedHashMap<>();
        Set<String> conflicting = new LinkedHashSet<>();

        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/FooRepository", "app/Foo");

        assertThat(byInterface).containsEntry("app/FooRepository", "app/Foo");
        assertThat(conflicting).isEmpty();
    }

    @Test
    void sameInterfaceSameEntity_staysMapped() {
        Map<String, String> byInterface = new LinkedHashMap<>();
        Set<String> conflicting = new LinkedHashSet<>();

        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/FooCustom", "app/Foo");
        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/FooCustom", "app/Foo");

        assertThat(byInterface).containsEntry("app/FooCustom", "app/Foo");
        assertThat(conflicting).isEmpty();
    }

    /**
     * 서로 다른 리포지토리 두 곳이 같은 프래그먼트 인터페이스를 서로 다른 엔티티로 등록하는
     * 경우입니다. 승자를 하나 찍지 않고 항목 자체를 지웁니다 — 팀 리드가 지적한 4.4 원칙
     * 위반(누락 방향) 수정의 핵심입니다.
     */
    @Test
    void sameInterfaceDifferentEntity_isRemovedNotGuessed() {
        Map<String, String> byInterface = new LinkedHashMap<>();
        Set<String> conflicting = new LinkedHashSet<>();

        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Foo");
        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Bar");

        assertThat(byInterface).doesNotContainKey("app/SharedCustom");
        assertThat(conflicting).contains("app/SharedCustom");
    }

    /**
     * 한 번 충돌로 지워진 이름은 그 뒤로 원래 값과 다시 마주쳐도 되살아나지 않습니다.
     * 되살아나면 마지막에 등록된 값이 우연히 남는 것과 같아져, 결국 하나를 찍는 것과
     * 다르지 않습니다.
     */
    @Test
    void blacklistedInterface_staysRemovedEvenIfOriginalEntitySeenAgain() {
        Map<String, String> byInterface = new LinkedHashMap<>();
        Set<String> conflicting = new LinkedHashSet<>();

        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Foo");
        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Bar");
        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Foo");

        assertThat(byInterface).doesNotContainKey("app/SharedCustom");
        assertThat(conflicting).contains("app/SharedCustom");
    }

    @Test
    void conflictOnOneInterface_doesNotAffectOthers() {
        Map<String, String> byInterface = new LinkedHashMap<>();
        Set<String> conflicting = new LinkedHashSet<>();

        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Foo");
        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/SharedCustom", "app/Bar");
        SpringProgramModel.registerEntityMapping(byInterface, conflicting, "app/BazRepository", "app/Baz");

        assertThat(byInterface).containsEntry("app/BazRepository", "app/Baz");
        assertThat(byInterface).doesNotContainKey("app/SharedCustom");
    }
}
