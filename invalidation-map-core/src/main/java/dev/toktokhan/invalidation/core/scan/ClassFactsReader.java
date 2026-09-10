package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 클래스 파일 하나를 {@link ClassFacts} 로 바꿉니다.
 *
 * <p>{@code scan} 패키지가 코어에서 ASM 을 쓰는 유일한 층입니다. {@code index}, {@code walk},
 * {@code resolve} 는 {@link ClassFacts} 만 봅니다.
 */
public final class ClassFactsReader {

    private static final int API = Opcodes.ASM9;

    private ClassFactsReader() {
    }

    public static ClassFacts read(byte[] classBytes) {
        ClassCollector collector = new ClassCollector();
        new ClassReader(classBytes).accept(collector, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return collector.build();
    }

    private static final class ClassCollector extends ClassVisitor {

        private String internalName;
        private String superName;
        private String signature;
        private List<String> interfaces = List.of();
        private final Map<String, AnnotationValues> annotations = new LinkedHashMap<>();
        private final List<FieldFacts> fields = new ArrayList<>();
        private final List<MethodFacts> methods = new ArrayList<>();

        ClassCollector() {
            super(API);
        }

        @Override
        public void visit(int version, int access, String name, String classSignature,
            String superClassName, String[] interfaceNames) {
            this.internalName = name;
            this.superName = superClassName;
            this.signature = classSignature;
            this.interfaces = interfaceNames == null ? List.of() : List.of(interfaceNames);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new ValueCollector(descriptor, annotations::put);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
            String fieldSignature, Object value) {
            Map<String, AnnotationValues> fieldAnnotations = new LinkedHashMap<>();
            return new FieldVisitor(API) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean isVisible) {
                    return new ValueCollector(annotationDescriptor, fieldAnnotations::put);
                }

                @Override
                public void visitEnd() {
                    // 방문이 끝난 뒤에 FieldFacts 를 만듭니다. 방문 전에 만들면 그 시점의
                    // 가변 맵이 FieldFacts.annotations() 로 그대로 노출됩니다 — 클래스·메서드
                    // 레벨 어노테이션은 불변 복사본을 담는데 필드만 예외였습니다.
                    // ASM 은 각 필드의 visitEnd 를 다음 visitField 보다 먼저 부르므로
                    // 필드 순서는 그대로 유지됩니다.
                    fields.add(new FieldFacts(name, descriptor, fieldSignature,
                        Collections.unmodifiableMap(new LinkedHashMap<>(fieldAnnotations))));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
            String methodSignature, String[] exceptions) {
            return new MethodCollector(internalName, access, name, descriptor, methods::add);
        }

        ClassFacts build() {
            return new ClassFacts(internalName, superName, interfaces, signature,
                Collections.unmodifiableMap(new LinkedHashMap<>(annotations)),
                List.copyOf(fields), List.copyOf(methods));
        }
    }

    private static final class MethodCollector extends MethodVisitor {

        private final String ownerInternalName;
        private final MethodRef ref;
        private final int access;
        private final Consumer<MethodFacts> sink;
        private final List<MethodRef> calls = new ArrayList<>();
        private final List<String> newTypes = new ArrayList<>();
        private final List<String> stringConstants = new ArrayList<>();
        private final Set<String> writtenOwnFields = new LinkedHashSet<>();
        private final Set<String> referencedFieldOwners = new LinkedHashSet<>();
        private final Set<String> referencedFieldTypes = new LinkedHashSet<>();
        private final List<String> classConstants = new ArrayList<>();
        private final Map<String, AnnotationValues> annotations = new LinkedHashMap<>();
        private final List<MethodRef> lambdaBodies = new ArrayList<>();

        MethodCollector(String owner, int access, String name, String descriptor,
            Consumer<MethodFacts> sink) {
            super(API);
            this.ownerInternalName = owner;
            this.ref = new MethodRef(owner, name, descriptor);
            this.access = access;
            this.sink = sink;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new ValueCollector(descriptor, annotations::put);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
            boolean isInterface) {
            calls.add(new MethodRef(owner, name, descriptor));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                newTypes.add(type);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String text) {
                stringConstants.add(text);
            }
            // 클래스 리터럴(Trip.class)은 LDC 의 Type 상수로 실립니다. em.find(Trip.class, id)
            // 처럼 엔티티를 클래스 리터럴로만 지목하는 호출에서는 이것이 유일한 단서입니다.
            if (value instanceof Type type) {
                objectTypeOf(type).ifPresent(classConstants::add);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.PUTFIELD && owner.equals(ownerInternalName)) {
                writtenOwnFields.add(name);
            }
            // QueryDSL Q클래스는 보통 new 로 만들지 않고 코드 생성기가 만든 public static
            // final 기본 인스턴스를 그대로 참조합니다(예: QTrip.trip 을 static import 해서
            // 쓰는 관용구). 이 경우 NEW 명령이 아예 없어 newTypes 에 잡히지 않으므로,
            // GETSTATIC/GETFIELD 로 읽은 필드의 선언 타입도 별도로 모아 둡니다. GETSTATIC/
            // GETFIELD 는 owner 를 제한하지 않고 전부 모읍니다 — 걸러내는 책임은 여기가
            // 아니라 소비자에게 있습니다. QuerydslResolver 는 이 집합에서 EntityPathBase
            // 상속 여부를 다시 확인하므로, Q클래스가 아닌 owner 가 섞여도 엔티티 오보로
            // 이어지지 않습니다(pirl-spring 1,050개 클래스 실측: 이렇게 늘어난 owner
            // 872개 중 EntityPathBase 상속은 23개뿐이었고 전부 Q클래스였습니다).
            if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD) {
                referencedFieldOwners.add(owner);
                // owner 는 필드를 "선언한" 타입이므로 Q클래스를 자기 인스턴스 필드로 들고
                // 쓰는 코드(private final QTrip held = QTrip.trip; → this.held)에서는
                // Q클래스가 owner 로 나타나지 않습니다 — 그 코드의 owner 는 리포지토리
                // 자신입니다. 필드에 담긴 값의 타입은 디스크립터에만 있으므로 따로 모읍니다.
                objectTypeOf(Type.getType(descriptor)).ifPresent(referencedFieldTypes::add);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
            Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            // 람다의 부트스트랩 인자는 [SAM 타입, 구현 메서드 핸들, 인스턴스화 타입] 입니다.
            // 문자열 결합(makeConcatWithConstants)의 인자에는 Handle 이 없으므로 걸러집니다.
            // Handle 에는 메서드 핸들(태그 5~9)뿐 아니라 필드 핸들(태그 1~4)도 있습니다.
            // record 의 toString/equals/hashCode 는 ObjectMethods.bootstrap 으로 만들어지고
            // 부트스트랩 인자에 컴포넌트마다 REF_getField 핸들이 실리므로, 태그로 걸러
            // 메서드 핸들만 lambdaBodies 에 담습니다.
            for (Object argument : bootstrapMethodArguments) {
                if (argument instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                    lambdaBodies.add(new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc()));
                }
            }
        }

        @Override
        public void visitEnd() {
            sink.accept(new MethodFacts(ref, access, List.copyOf(calls), List.copyOf(newTypes),
                List.copyOf(stringConstants), Collections.unmodifiableSet(new LinkedHashSet<>(writtenOwnFields)),
                Collections.unmodifiableSet(new LinkedHashSet<>(referencedFieldOwners)),
                Collections.unmodifiableSet(new LinkedHashSet<>(referencedFieldTypes)),
                List.copyOf(classConstants),
                Collections.unmodifiableMap(new LinkedHashMap<>(annotations)), List.copyOf(lambdaBodies)));
        }

        /**
         * 참조 타입이면 그 internal name 입니다. 기본 타입({@code int} 등)과
         * {@code void} 는 빈 값이고, 배열은 원소 타입을 따라 내려갑니다 —
         * {@code Trip[]} 필드도 {@code Trip} 을 단서로 씁니다.
         */
        private static Optional<String> objectTypeOf(Type type) {
            Type current = type;
            while (current.getSort() == Type.ARRAY) {
                current = current.getElementType();
            }
            return current.getSort() == Type.OBJECT
                ? Optional.of(current.getInternalName())
                : Optional.empty();
        }
    }

    private static final class ValueCollector extends AnnotationVisitor {

        private final String descriptor;
        private final BiConsumer<String, AnnotationValues> sink;
        private final Map<String, Object> values = new LinkedHashMap<>();

        ValueCollector(String descriptor, BiConsumer<String, AnnotationValues> sink) {
            super(API);
            this.descriptor = descriptor;
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            values.put(name, normalize(value));
        }

        @Override
        public void visitEnum(String name, String enumDescriptor, String value) {
            values.put(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<Object> items = new ArrayList<>();
            values.put(name, items);
            return new AnnotationVisitor(API) {
                @Override
                public void visit(String itemName, Object value) {
                    items.add(normalize(value));
                }

                @Override
                public void visitEnum(String itemName, String enumDescriptor, String value) {
                    items.add(value);
                }
            };
        }

        @Override
        public void visitEnd() {
            sink.accept(descriptor, new AnnotationValues(Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        }

        /** 클래스 값은 ASM Type 으로 오므로 internal name 문자열로 바꿉니다. */
        private static Object normalize(Object value) {
            return value instanceof Type type ? type.getInternalName() : value;
        }
    }
}
