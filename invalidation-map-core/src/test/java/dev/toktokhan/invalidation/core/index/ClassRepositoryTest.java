package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.fixture.hierarchy.Payload;
import dev.toktokhan.invalidation.core.fixture.hierarchy.Port;
import dev.toktokhan.invalidation.core.fixture.hierarchy.PortAdapter;
import dev.toktokhan.invalidation.core.fixture.hierarchy.TypedBase;
import dev.toktokhan.invalidation.core.fixture.hierarchy.TypedChild;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.HidingProgramModel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassRepositoryTest {

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());

    @Test
    void facts_classOnTestClasspath_readsFacts() {
        assertThat(classes.facts(MethodRefs.internalNameOf(TypedChild.class)))
            .get()
            .satisfies(facts -> assertThat(facts.superName())
                .isEqualTo(MethodRefs.internalNameOf(TypedBase.class)));
    }

    @Test
    void facts_classNotOnClasspath_returnsEmpty() {
        assertThat(classes.facts("com/nowhere/Missing")).isEmpty();
    }

    @Test
    void facts_calledTwice_readsBytesOnce() {
        String name = MethodRefs.internalNameOf(TypedChild.class);
        assertThat(classes.facts(name)).isSameAs(classes.facts(name));
    }

    @Test
    void supertypesOf_classWithSuperAndInterface_reportsAllTransitively() {
        assertThat(classes.supertypesOf(MethodRefs.internalNameOf(PortAdapter.class)))
            .contains(MethodRefs.internalNameOf(Port.class), "java/lang/Object");
    }

    @Test
    void isSubtypeOf_sameType_isTrue() {
        String name = MethodRefs.internalNameOf(PortAdapter.class);
        assertThat(classes.isSubtypeOf(name, name)).isTrue();
    }

    @Test
    void isSubtypeOf_implementedInterface_isTrue() {
        assertThat(classes.isSubtypeOf(
            MethodRefs.internalNameOf(PortAdapter.class),
            MethodRefs.internalNameOf(Port.class))).isTrue();
    }

    @Test
    void isSubtypeOf_unrelatedType_isFalse() {
        assertThat(classes.isSubtypeOf(
            MethodRefs.internalNameOf(PortAdapter.class),
            MethodRefs.internalNameOf(Payload.class))).isFalse();
    }

    @Test
    void typeArgumentOfSupertype_genericSuperclass_reportsTypeArgument() {
        assertThat(classes.typeArgumentOfSupertype(
            MethodRefs.internalNameOf(TypedChild.class),
            MethodRefs.internalNameOf(TypedBase.class)))
            .contains(MethodRefs.internalNameOf(Payload.class));
    }

    @Test
    void methodFacts_interfaceMethodWithoutBody_returnsFactsWithEmptyCalls() {
        MethodRef onInterface = new MethodRef(MethodRefs.internalNameOf(Port.class), "run", "()V");
        assertThat(classes.methodFacts(onInterface))
            .get()
            .satisfies(facts -> assertThat(facts.calls()).isEmpty());
    }

    @Test
    void isFullyReadable_ownerUnreadable_isFalse() {
        assertThat(classes.isFullyReadable("com/nowhere/Missing")).isFalse();
    }

    @Test
    void isFullyReadable_ownerAndAllSupertypesReadable_isTrue() {
        assertThat(classes.isFullyReadable(MethodRefs.internalNameOf(TypedChild.class))).isTrue();
    }

    /**
     * {@code resolveMethod} 는 {@code TypedChild} 자신에 없는 메서드를 찾으려고 상위 타입
     * ({@code TypedBase})까지 올라갑니다. {@code TypedChild} 자신은 읽히지만
     * {@code TypedBase} 를 못 읽으면, "사슬 전체를 읽었다" 는 전제가 깨지므로
     * {@code isFullyReadable} 은 거짓이어야 합니다 — 후보 자신의 가독성만 보면 이 경우를
     * 놓칩니다.
     */
    @Test
    void isFullyReadable_ownerReadableButSupertypeUnreadable_isFalse() {
        ClassRepository hidingBase = new ClassRepository(new HidingProgramModel(
            FakeProgramModel.create(), MethodRefs.internalNameOf(TypedBase.class)));
        assertThat(hidingBase.isFullyReadable(MethodRefs.internalNameOf(TypedChild.class))).isFalse();
    }

    @Test
    void resolveMethod_methodDeclaredOnSupertype_findsItOnSupertype() {
        // JDK 9+ 에서는 ClassLoader.getResourceAsStream("java/lang/Object.class") 가 모듈
        // 캡슐화 때문에 환경에 따라 null 을 돌려줄 수 있습니다. java/lang/Object 대신 픽스처
        // 계층(TypedChild -> TypedBase)의 touch() 로 같은 동작(선언 클래스에 없으면 상위
        // 타입에서 찾는다)을 검증합니다.
        MethodRef declaredOnChild = new MethodRef(
            MethodRefs.internalNameOf(TypedChild.class), "touch", "()V");
        assertThat(classes.resolveMethod(declaredOnChild))
            .get()
            .satisfies(facts -> assertThat(facts.ref().owner())
                .isEqualTo(MethodRefs.internalNameOf(TypedBase.class)));
    }

    @Test
    void facts_classBytesUnreadableByAsm_returnsEmptyInsteadOfThrowing() {
        // ASM 이 헤더조차 못 읽는 바이트를 돌려주면 ClassReader 생성자가 예외를 던집니다.
        // facts 가 그 예외를 삼키지 않으면 이 호출 자체가 테스트를 실패시킵니다.
        ClassRepository corrupt = new ClassRepository(new CorruptBytesProgramModel());
        assertThat(corrupt.facts("broken/Class")).isEmpty();
    }

    @Test
    void unreadableClasses_afterReadFailure_recordsInternalNameWithReason() {
        ClassRepository corrupt = new ClassRepository(new CorruptBytesProgramModel());
        corrupt.facts("broken/Class");
        assertThat(corrupt.unreadableClasses()).containsOnlyKeys("broken/Class");
        assertThat(corrupt.unreadableClasses().get("broken/Class")).isNotBlank().doesNotContain("null");
    }

    @Test
    void unreadableClasses_beforeAnyReadFailure_isEmpty() {
        // 성공하는 읽기를 먼저 시킵니다. 읽기 자체를 한 번도 하지 않으면, 실패 여부와
        // 무관하게 항상 값을 채우는 구현도(채울 기회가 없어서) 이 테스트를 통과시킵니다.
        classes.facts(MethodRefs.internalNameOf(TypedChild.class));
        assertThat(classes.unreadableClasses()).isEmpty();
    }

    /** 항상 ASM 이 읽을 수 없는 바이트를 돌려주는 가짜입니다. facts 의 예외 처리만 검증합니다. */
    private static final class CorruptBytesProgramModel implements ProgramModel {

        @Override
        public List<Endpoint> endpoints() {
            return List.of();
        }

        @Override
        public Optional<byte[]> classBytes(String internalName) {
            return Optional.of(new byte[] {0x01, 0x02, 0x03});
        }

        @Override
        public Optional<String> entityFor(String repositoryInternalName) {
            return Optional.empty();
        }

        @Override
        public Set<String> implementationsOf(String interfaceInternalName) {
            return Set.of();
        }

        @Override
        public Set<String> entities() {
            return Set.of();
        }

        @Override
        public Set<MethodRef> eventListeners() {
            return Set.of();
        }
    }
}
