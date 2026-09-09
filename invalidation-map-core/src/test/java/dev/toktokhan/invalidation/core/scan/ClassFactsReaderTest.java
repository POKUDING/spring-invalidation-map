package dev.toktokhan.invalidation.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.scan.GenericSample;
import dev.toktokhan.invalidation.core.fixture.scan.RecordSample;
import dev.toktokhan.invalidation.core.fixture.scan.RelatedEntity;
import dev.toktokhan.invalidation.core.fixture.scan.ScanSample;
import dev.toktokhan.invalidation.core.resolve.QuerydslResolver;
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

    /**
     * {@code describe()} 는 {@code prefix.concat(name)} 에서 {@code this.name} 을
     * {@code GETFIELD} 로 읽습니다. {@link QuerydslResolver} 가 QueryDSL Q클래스의 정적
     * 기본 인스턴스 참조({@code QTrip.trip} 처럼 {@code new} 없이 필드로만 쓰는 실제
     * 관용구, Task 12 pirl-spring 실측)를 찾으려면 이 사실이 필요합니다.
     */
    @Test
    void read_methodReadsField_reportsFieldOwner() {
        assertThat(method("describe").referencedFieldOwners())
            .contains(Bytes.internalName(ScanSample.class));
    }

    /** {@code rename()} 은 {@code PUTFIELD} 만 하고 어떤 필드도 읽지 않습니다. */
    @Test
    void read_methodOnlyWritesField_reportsNoFieldOwner() {
        assertThat(method("rename").referencedFieldOwners()).isEmpty();
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

    @Test
    void readMethods_getFieldWithDifferentFieldType_collectsBothOwnerAndFieldType() {
        // owner 는 필드를 선언한 타입(ScanSample)이고, 필드 타입은 그 필드에 담긴 값의
        // 타입(java/util/List)입니다. 두 값이 다른 자리를 골라, 선언 타입만 모으는 구현과
        // 필드 타입까지 모으는 구현을 구분합니다.
        assertThat(method("relatedCount").referencedFieldOwners())
            .containsExactly(MethodRefs.internalNameOf(ScanSample.class));
        assertThat(method("relatedCount").referencedFieldTypes())
            .containsExactly("java/util/List");
    }

    @Test
    void readMethods_classLiteral_collectsClassConstant() {
        // 클래스 리터럴은 LDC 의 Type 상수로 실립니다. String LDC 만 보는 구현은 이 값을
        // 통째로 버립니다.
        assertThat(method("relatedType").classConstants())
            .containsExactly(MethodRefs.internalNameOf(RelatedEntity.class));
        assertThat(method("relatedType").stringConstants()).isEmpty();
    }

    @Test
    void readMethods_primitiveFieldAccess_isNotCollectedAsFieldType() {
        // 기본 타입 필드는 참조 타입이 아니므로 후보가 될 수 없습니다. Type.getSort() 를
        // 확인하지 않고 디스크립터를 그대로 담는 구현은 "I" 같은 값을 색인에 넣습니다.
        assertThat(method("relatedCount").referencedFieldTypes())
            .noneMatch(type -> type.length() == 1);
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
