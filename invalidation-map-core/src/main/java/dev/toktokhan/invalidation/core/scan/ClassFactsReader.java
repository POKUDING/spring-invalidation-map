package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            fields.add(new FieldFacts(name, descriptor, fieldSignature, fieldAnnotations));
            return new FieldVisitor(API) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean isVisible) {
                    return new ValueCollector(annotationDescriptor, fieldAnnotations::put);
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
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.PUTFIELD && owner.equals(ownerInternalName)) {
                writtenOwnFields.add(name);
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
                Collections.unmodifiableMap(new LinkedHashMap<>(annotations)), List.copyOf(lambdaBodies)));
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
