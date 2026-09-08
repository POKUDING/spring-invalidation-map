package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.hierarchy.Payload;
import dev.toktokhan.invalidation.core.fixture.hierarchy.Port;
import dev.toktokhan.invalidation.core.fixture.hierarchy.PortAdapter;
import dev.toktokhan.invalidation.core.fixture.hierarchy.TypedBase;
import dev.toktokhan.invalidation.core.fixture.hierarchy.TypedChild;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
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
}
