package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.scan.ClassFacts;
import dev.toktokhan.invalidation.core.scan.FieldFacts;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import dev.toktokhan.invalidation.core.scan.SignatureTypeArguments;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 엔티티에 대해 알아야 하는 사실을 모아 둡니다. 변경자 판정, 한 단계 연관, 테이블명 역매핑입니다.
 */
public final class EntityIndex {

    private static final String TABLE = "Ljakarta/persistence/Table;";
    private static final String MAPPED_SUPERCLASS = "Ljakarta/persistence/MappedSuperclass;";

    private static final Set<String> ASSOCIATION_ANNOTATIONS = Set.of(
        "Ljakarta/persistence/OneToMany;",
        "Ljakarta/persistence/ManyToOne;",
        "Ljakarta/persistence/OneToOne;",
        "Ljakarta/persistence/ManyToMany;",
        "Ljakarta/persistence/Embedded;",
        "Ljakarta/persistence/ElementCollection;");

    private final ClassRepository classes;
    private final Set<String> entities;

    /** 선언 클래스 -> 그 클래스가 선언한 변경자의 "이름+디스크립터" */
    private final Map<String, Set<String>> mutatorsByDeclaringClass = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> associationCache = new ConcurrentHashMap<>();
    private final Map<String, String> entityByTable;

    /**
     * @param reportedEntities {@code ProgramModel.entities()} 가 알려준 엔티티와 임베더블의
     *                         internal name 입니다
     */
    public EntityIndex(ClassRepository classes, Set<String> reportedEntities) {
        this.classes = classes;
        // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        this.entities = Collections.unmodifiableSet(new LinkedHashSet<>(reportedEntities));
        this.entityByTable = buildTableIndex();
    }

    public boolean isEntity(String internalName) {
        return entities.contains(internalName);
    }

    public Set<String> entities() {
        return entities;
    }

    /**
     * 이 호출 지점이 엔티티 변경자 호출인지 판정합니다.
     *
     * <p>{@code ref.owner()} 가 엔티티여야 합니다. 메서드 본문은 상위 타입을 따라 올라가며
     * 찾습니다. 상위 타입에 선언된 변경자를 호출해도 쓰이는 엔티티는 {@code ref.owner()} 입니다.
     */
    public boolean isMutator(MethodRef ref) {
        if (!isEntity(ref.owner())) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(ref.owner());
        candidates.addAll(classes.supertypesOf(ref.owner()));
        String signature = ref.name() + ref.descriptor();
        for (String declaringClass : candidates) {
            if (mutatorsOf(declaringClass).contains(signature)) {
                return true;
            }
        }
        return false;
    }

    /** 이 엔티티에서 한 단계 연관으로 닿는 엔티티입니다. 엔티티가 아닌 대상은 버립니다. */
    public Set<String> associationsOf(String entityInternalName) {
        if (!isEntity(entityInternalName)) {
            return Set.of();
        }
        return associationCache.computeIfAbsent(entityInternalName, name -> {
            Set<String> targets = new LinkedHashSet<>();
            List<String> hierarchy = new ArrayList<>();
            hierarchy.add(name);
            hierarchy.addAll(classes.supertypesOf(name));
            for (String current : hierarchy) {
                classes.facts(current).ifPresent(facts -> {
                    for (FieldFacts field : facts.fields()) {
                        if (isAssociation(field)) {
                            targets.addAll(associationTargets(field));
                        }
                    }
                });
            }
            targets.remove(name);
            // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
            return Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        });
    }

    public Optional<String> entityForTable(String tableName) {
        if (tableName == null) {
            return Optional.empty();
        }
        String direct = entityByTable.get(tableName);
        return direct != null
            ? Optional.of(direct)
            : Optional.ofNullable(entityByTable.get(tableName.toLowerCase()));
    }

    /** 이 클래스가 선언한 변경자의 "이름+디스크립터" 집합입니다. 전이적으로 고정점까지 계산합니다. */
    private Set<String> mutatorsOf(String declaringClass) {
        return mutatorsByDeclaringClass.computeIfAbsent(declaringClass, name -> {
            Optional<ClassFacts> maybeFacts = classes.facts(name);
            if (maybeFacts.isEmpty()) {
                return Set.of();
            }
            ClassFacts facts = maybeFacts.get();
            // 엔티티이거나 @MappedSuperclass 여야 변경자를 셉니다.
            boolean holdsState = isEntity(name) || facts.hasAnnotation(MAPPED_SUPERCLASS);
            if (!holdsState) {
                return Set.of();
            }

            List<MethodFacts> candidates = facts.methods().stream()
                .filter(method -> !method.isStatic())
                .filter(method -> !method.isConstructor())
                .toList();

            Set<String> mutators = new LinkedHashSet<>();
            for (MethodFacts method : candidates) {
                if (!method.writtenOwnFields().isEmpty()) {
                    mutators.add(method.ref().name() + method.ref().descriptor());
                }
            }

            // 다른 변경자를 호출하는 메서드도 변경자입니다. 더 늘지 않을 때까지 반복합니다.
            boolean changed = true;
            while (changed) {
                changed = false;
                for (MethodFacts method : candidates) {
                    String signature = method.ref().name() + method.ref().descriptor();
                    if (mutators.contains(signature)) {
                        continue;
                    }
                    boolean callsMutator = method.calls().stream()
                        .filter(call -> call.owner().equals(name))
                        .anyMatch(call -> mutators.contains(call.name() + call.descriptor()));
                    if (callsMutator) {
                        mutators.add(signature);
                        changed = true;
                    }
                }
            }
            // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
            return Collections.unmodifiableSet(new LinkedHashSet<>(mutators));
        });
    }

    private static boolean isAssociation(FieldFacts field) {
        return field.annotations().keySet().stream().anyMatch(ASSOCIATION_ANNOTATIONS::contains);
    }

    /** 컬렉션 필드는 제네릭 인자에서, 단일 필드는 디스크립터에서 대상 타입을 얻습니다. */
    private Set<String> associationTargets(FieldFacts field) {
        Set<String> targets = new LinkedHashSet<>();
        for (String argument : SignatureTypeArguments.typeArgumentsOfFieldType(field.signature())) {
            if (isEntity(argument)) {
                targets.add(argument);
            }
        }
        if (targets.isEmpty()) {
            objectTypeOf(field.descriptor())
                .filter(this::isEntity)
                .ifPresent(targets::add);
        }
        return targets;
    }

    /** {@code Lcom/example/Trip;} 에서 {@code com/example/Trip} 을 뽑습니다. */
    private static Optional<String> objectTypeOf(String descriptor) {
        if (descriptor != null && descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return Optional.of(descriptor.substring(1, descriptor.length() - 1));
        }
        return Optional.empty();
    }

    private Map<String, String> buildTableIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (String entity : entities) {
            Optional<String> explicit = classes.facts(entity)
                .map(facts -> facts.annotation(TABLE))
                .flatMap(values -> values.string("name"))
                .filter(name -> !name.isBlank());
            if (explicit.isPresent()) {
                index.putIfAbsent(explicit.get(), entity);
                index.putIfAbsent(explicit.get().toLowerCase(), entity);
                continue;
            }
            // @Table 이 없으면 두 기본 규칙을 모두 등록합니다.
            // JPA 표준 기본값은 엔티티 단순명 그대로이고,
            // Spring Boot 기본값(SpringPhysicalNamingStrategy)은 snake_case 입니다.
            String simpleName = MethodRefs.simpleNameOf(entity);
            index.putIfAbsent(simpleName, entity);
            index.putIfAbsent(camelToSnake(simpleName), entity);
        }
        // Map.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    static String camelToSnake(String name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
