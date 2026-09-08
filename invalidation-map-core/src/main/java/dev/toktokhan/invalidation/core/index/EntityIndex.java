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
import java.util.Locale;
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

    /** 선언 클래스 -> 그 클래스에서 호출 가능한 변경자의 "이름+디스크립터" */
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

    /**
     * 이 호출 지점이 엔티티 변경자 호출인지 판정합니다.
     *
     * <p>{@code ref.owner()} 가 엔티티여야 합니다. 메서드 본문은 상위 타입을 따라 올라가며
     * 찾습니다. 상위 타입에 선언된 변경자를 호출해도 쓰이는 엔티티는 {@code ref.owner()} 입니다.
     *
     * <p>반대로 {@code BaseRecord b = trip; b.markDeleted();} 처럼 호출 지점의 정적 수신
     * 타입 자체가 상위 타입({@code @MappedSuperclass})이면 판정하지 않습니다. 이 경우
     * {@code ref.owner()} 가 {@code BaseRecord} 라 어떤 엔티티가 쓰였는지 알 수 없고, 이를
     * 판정하려면 {@code BaseRecord} 를 상속한 엔티티 전체를 무효화해야 하는데 그건 과잉이기
     * 때문입니다.
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
            : Optional.ofNullable(entityByTable.get(tableName.toLowerCase(Locale.ROOT)));
    }

    /**
     * 이 클래스에서 호출 가능한 변경자의 "이름+디스크립터" 집합입니다. 전이적으로 고정점까지
     * 계산합니다.
     *
     * <p>서브클래스 메서드가 상위 타입에 선언된 변경자를 호출하면, 그 호출 지점의 owner 는
     * 상위 타입이 아니라 서브클래스로 기록됩니다 — {@code this.markDeleted()} 처럼 정적 수신
     * 타입을 owner 로 남기기 때문입니다. 이 클래스가 선언한 메서드만 보면 그런 호출을
     * 놓치므로, 상위 타입의 변경자 집합을 먼저 계산해 시드로 섞어 넣습니다.
     */
    private Set<String> mutatorsOf(String declaringClass) {
        Set<String> cached = mutatorsByDeclaringClass.get(declaringClass);
        if (cached != null) {
            return cached;
        }
        // supertypesOf() 의 결과를 computeIfAbsent 람다 밖에서 먼저 계산합니다. 람다 안에서
        // 같은 ConcurrentHashMap 의 다른 키를 갱신하면 재진입 문제가 생길 수 있습니다.
        Set<String> inherited = new LinkedHashSet<>();
        for (String supertype : classes.supertypesOf(declaringClass)) {
            inherited.addAll(mutatorsOf(supertype));
        }
        return mutatorsByDeclaringClass.computeIfAbsent(declaringClass,
            name -> computeMutators(name, inherited));
    }

    /**
     * @param inheritedMutators 상위 타입에서 이미 확인된 변경자 시그니처입니다. 이 클래스가
     *                          선언한 메서드가 이 시그니처를 호출하면(owner 가 이 클래스로
     *                          기록된 상속 호출), 그 메서드도 변경자입니다.
     */
    private Set<String> computeMutators(String declaringClass, Set<String> inheritedMutators) {
        Optional<ClassFacts> maybeFacts = classes.facts(declaringClass);
        if (maybeFacts.isEmpty()) {
            return copyOfOrEmpty(inheritedMutators);
        }
        ClassFacts facts = maybeFacts.get();
        // 엔티티이거나 @MappedSuperclass 여야 이 클래스 자신의 변경자를 새로 셉니다. 상태를
        // 갖지 않는 클래스라도 상속받은 변경자 호출은 그대로 전달합니다.
        boolean holdsState = isEntity(declaringClass) || facts.hasAnnotation(MAPPED_SUPERCLASS);
        if (!holdsState) {
            return copyOfOrEmpty(inheritedMutators);
        }

        List<MethodFacts> candidates = facts.methods().stream()
            .filter(method -> !method.isStatic())
            .filter(method -> !method.isConstructor())
            .toList();

        Set<String> mutators = new LinkedHashSet<>(inheritedMutators);
        for (MethodFacts method : candidates) {
            if (!method.writtenOwnFields().isEmpty()) {
                mutators.add(method.ref().name() + method.ref().descriptor());
            }
        }

        // 다른 변경자를 호출하는 메서드도 변경자입니다. 대조 집합에 상속받은 변경자도 들어
        // 있으므로, 상위 타입에 선언된 변경자를 호출하는 이 클래스의 메서드도 잡힙니다.
        // 더 늘지 않을 때까지 반복합니다.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodFacts method : candidates) {
                String signature = method.ref().name() + method.ref().descriptor();
                if (mutators.contains(signature)) {
                    continue;
                }
                boolean callsMutator = method.calls().stream()
                    .filter(call -> call.owner().equals(declaringClass))
                    .anyMatch(call -> mutators.contains(call.name() + call.descriptor()));
                if (callsMutator) {
                    mutators.add(signature);
                    changed = true;
                }
            }
        }
        return copyOfOrEmpty(mutators);
    }

    private static Set<String> copyOfOrEmpty(Set<String> source) {
        if (source.isEmpty()) {
            return Set.of();
        }
        // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
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

    /**
     * 테이블명에서 엔티티로 가는 조회 표를 만듭니다.
     *
     * <p>{@code @Table(name=)} 이 있는 엔티티를 먼저 전부 등록하고, 그 다음에 {@code @Table}
     * 이 없는 엔티티의 기본값을 등록합니다. 그래야 다른 엔티티의 기본값이 어떤 엔티티의
     * 명시적인 {@code @Table} 값을 밀어내지 않습니다 — 개발자가 선언한 사실이 추정값보다
     * 우선해야 합니다.
     *
     * <p>두 단계 모두 엔티티 이름을 정렬해서 훑습니다. {@code entities} 는 호출자가 넘긴
     * {@code Set} 의 순회 순서를 그대로 물려받는데, 그 순서는 JVM 기동마다 달라질 수
     * 있습니다. 정렬하지 않으면 같은 테이블명 후보가 겹치는 두 엔티티 중 어느 쪽이
     * 이기는지가 실행마다 달라집니다.
     */
    private Map<String, String> buildTableIndex() {
        List<String> sortedEntities = new ArrayList<>(entities);
        Collections.sort(sortedEntities);

        Map<String, String> index = new LinkedHashMap<>();

        for (String entity : sortedEntities) {
            explicitTableNameOf(entity).ifPresent(name -> registerTableName(index, entity, name));
        }

        for (String entity : sortedEntities) {
            if (explicitTableNameOf(entity).isPresent()) {
                continue;
            }
            // @Table 이 없으면 기본 규칙 후보를 전부 등록합니다. 조회 표에 여분의 항목이
            // 있어도 해가 없고, 어느 네이밍 전략을 쓰는 프로젝트든 덮습니다.
            // 1. JPA 표준 기본값(엔티티 단순명 그대로)과 그 소문자
            // 2. Spring Boot 기본값(SpringPhysicalNamingStrategy 의 snake_case)
            // 3. 순진한 snake_case(대문자마다 밑줄) — 위 두 규칙이 못 미치는 경우의 안전망
            String simpleName = MethodRefs.simpleNameOf(entity);
            registerTableName(index, entity, simpleName);
            registerTableName(index, entity, simpleName.toLowerCase(Locale.ROOT));
            registerTableName(index, entity, springPhysicalNamingSnakeCase(simpleName));
            registerTableName(index, entity, camelToSnake(simpleName));
        }

        // Map.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    private Optional<String> explicitTableNameOf(String entity) {
        return classes.facts(entity)
            .map(facts -> facts.annotation(TABLE))
            .flatMap(values -> values.string("name"))
            .filter(name -> !name.isBlank());
    }

    private static void registerTableName(Map<String, String> index, String entity, String tableName) {
        index.putIfAbsent(tableName, entity);
        index.putIfAbsent(tableName.toLowerCase(Locale.ROOT), entity);
    }

    /**
     * 대문자마다 밑줄을 넣는 순진한 snake_case 입니다. JPA 구현체 중에는 이 규칙을 쓰는
     * 것도 있어 안전망으로 남겨 둡니다. Spring Boot 기본값은
     * {@link #springPhysicalNamingSnakeCase} 를 쓰십시오.
     */
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

    /**
     * Spring Boot 기본 물리 네이밍 전략({@code SpringPhysicalNamingStrategy})과 같은 규칙으로
     * CamelCase 를 snake_case 로 바꿉니다. 앞 글자가 소문자이고 지금 글자가 대문자이고
     * 뒷 글자도 소문자일 때만 밑줄을 넣습니다. 그래서 연속된 대문자 사이나 대문자로 끝나는
     * 자리, 숫자 바로 뒤에는 밑줄이 들어가지 않습니다.
     *
     * <p>예: {@code TripLeg -> trip_leg}, {@code HTTPServer -> httpserver}
     * (연속 대문자에는 밑줄 없음), {@code TripID -> tripid} (대문자로 끝나 밑줄 없음).
     */
    static String springPhysicalNamingSnakeCase(String name) {
        StringBuilder builder = new StringBuilder(name);
        for (int i = 1; i < builder.length() - 1; i++) {
            if (isUnderscoreRequired(builder.charAt(i - 1), builder.charAt(i), builder.charAt(i + 1))) {
                builder.insert(i++, '_');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isUnderscoreRequired(char before, char current, char after) {
        return Character.isLowerCase(before) && Character.isUpperCase(current) && Character.isLowerCase(after);
    }
}
