package dev.toktokhan.invalidation.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.fixture.scan.ScanSample;
import dev.toktokhan.invalidation.core.support.Bytes;
import org.junit.jupiter.api.Test;

class ClassFactsReaderTest {

    private final ClassFacts facts = ClassFactsReader.read(Bytes.of(ScanSample.class));

    @Test
    void read_anyClass_reportsInternalNameAndSuperName() {
        assertThat(facts.internalName()).isEqualTo(Bytes.internalName(ScanSample.class));
        assertThat(facts.superName()).isEqualTo("java/lang/Object");
    }

    @Test
    void read_methodWritesOwnField_reportsFieldName() {
        assertThat(method("rename").writtenOwnFields()).containsExactly("name");
    }

    @Test
    void read_methodDoesNotWriteOwnField_reportsNoField() {
        assertThat(method("describe").writtenOwnFields()).isEmpty();
    }

    @Test
    void read_methodCallsAnotherMethod_reportsCallTarget() {
        assertThat(method("describe").calls()).contains(
            new MethodRef("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
    }

    @Test
    void read_methodLoadsStringConstant_reportsConstant() {
        assertThat(method("describe").stringConstants()).contains("sample");
    }

    @Test
    void read_methodInstantiatesType_reportsNewType() {
        assertThat(method("create").newTypes()).contains("java/lang/StringBuilder");
    }

    @Test
    void read_methodCreatesLambda_reportsSyntheticBody() {
        assertThat(method("lazy").lambdaBodies())
            .anySatisfy(ref -> assertThat(ref.name()).startsWith("lambda$lazy$"));
    }

    @Test
    void read_methodHasRuntimeAnnotation_reportsAnnotationValue() {
        assertThat(method("legacy").annotation("Ljava/lang/Deprecated;").string("since"))
            .contains("1.0");
    }

    @Test
    void read_classHasFields_reportsFieldDescriptor() {
        assertThat(facts.fields())
            .anySatisfy(field -> {
                assertThat(field.name()).isEqualTo("name");
                assertThat(field.descriptor()).isEqualTo("Ljava/lang/String;");
            });
    }

    private MethodFacts method(String name) {
        return facts.methodNamed(name).orElseThrow(
            () -> new AssertionError("픽스처에 메서드가 없습니다: " + name));
    }
}
