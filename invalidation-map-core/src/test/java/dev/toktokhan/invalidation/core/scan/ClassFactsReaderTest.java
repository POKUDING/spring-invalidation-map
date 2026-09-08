package dev.toktokhan.invalidation.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.fixture.scan.GenericSample;
import dev.toktokhan.invalidation.core.fixture.scan.RecordSample;
import dev.toktokhan.invalidation.core.fixture.scan.RelatedEntity;
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

    @Test
    void read_classHasAnnotation_reportsClassLevelAnnotationValue() {
        assertThat(facts.annotation("Ljakarta/persistence/Table;").string("name"))
            .contains("scan_sample");
    }

    @Test
    void read_fieldHasAnnotationWithEnumAndClassValues_reportsNormalizedValues() {
        AnnotationValues oneToMany = field("related").annotations().get("Ljakarta/persistence/OneToMany;");
        assertThat(oneToMany.string("fetch")).contains("LAZY");
        assertThat(oneToMany.strings("cascade")).containsExactly("PERSIST", "MERGE");
        assertThat(oneToMany.string("targetEntity")).contains(Bytes.internalName(RelatedEntity.class));
    }

    @Test
    void read_fieldHasGenericType_reportsSignature() {
        assertThat(field("related").signature())
            .isEqualTo("Ljava/util/List<L" + Bytes.internalName(RelatedEntity.class) + ";>;");
    }

    @Test
    void read_methodHasAnnotationWithBooleanAndClassArrayValues_reportsNormalizedValues() {
        AnnotationValues transactional = method("commit").annotation("Lorg/springframework/transaction/annotation/Transactional;");
        assertThat(transactional.bool("readOnly", false)).isTrue();
        assertThat(transactional.strings("rollbackFor")).containsExactly(
            "java/lang/IllegalStateException", "java/lang/IllegalArgumentException");
    }

    @Test
    void read_recordGeneratesToString_lambdaBodiesExcludeFieldGetterHandles() {
        ClassFacts recordFacts = ClassFactsReader.read(Bytes.of(RecordSample.class));
        assertThat(recordFacts.methodNamed("toString").orElseThrow().lambdaBodies()).isEmpty();
    }

    @Test
    void read_classExtendsGenericSuperclass_reportsSignature() {
        ClassFacts genericFacts = ClassFactsReader.read(Bytes.of(GenericSample.class));
        assertThat(genericFacts.signature()).isEqualTo("Ljava/util/ArrayList<Ljava/lang/String;>;");
    }

    private MethodFacts method(String name) {
        return facts.methodNamed(name).orElseThrow(
            () -> new AssertionError("픽스처에 메서드가 없습니다: " + name));
    }

    private FieldFacts field(String name) {
        return facts.fields().stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("픽스처에 필드가 없습니다: " + name));
    }
}
