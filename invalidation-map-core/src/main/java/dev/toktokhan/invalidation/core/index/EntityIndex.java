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
 * 엔티티에 대해 알아야 하는 사실을 모아 둡니다. 변경자 판정, 한 단계 연관, 필드별 연관 조회,
 * 테이블명 역매핑, JPQL 엔티티명 역색인입니다.
 */
public final class EntityIndex {

    private static final String TABLE = "Ljakarta/persistence/Table;";
    private static final String MAPPED_SUPERCLASS = "Ljakarta/persistence/MappedSuperclass;";
    private static final String ENTITY = "Ljakarta/persistence/Entity;";

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
    private final Map<String, Set<String>> entitiesByTable;

    /** JPQL 엔티티명 -> internal name. @Entity(name=) 값과 단순 클래스명을 모두 색인합니다. */
    private final Map<String, String> entityByJpqlName;

    /**
     * @param reportedEntities {@code ProgramModel.entities()} 가 알려준 엔티티와 임베더블의
     *                         internal name 입니다
     */
    public EntityIndex(ClassRepository classes, Set<String> reportedEntities) {
        this.classes = classes;
        // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        this.entities = Collections.unmodifiableSet(new LinkedHashSet<>(reportedEntities));
        this.entitiesByTable = buildTableIndex();
        this.entityByJpqlName = buildNameIndex();
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

    /**
     * 이 테이블명 후보로 등록된 엔티티 전체입니다.
     *
     * <p>서로 다른 엔티티가 같은 테이블명 후보를 만들 수 있습니다(예: Postgres 예약어를
     * 피하려고 {@code @Table(name = "user")} 를 붙인 엔티티와, 기본값이 우연히 같은 이름이
     * 되는 다른 엔티티). 어느 한쪽만 돌려주면 밀린 쪽의 무효화가 통째로 누락되므로, 충돌
     * 시 양쪽을 다 돌려줍니다. 누락을 금지하는 전역 원칙에 따른 과잉 방향입니다.
     */
    public Set<String> entitiesForTable(String tableName) {
        if (tableName == null) {
            return Set.of();
        }
        Set<String> direct = entitiesByTable.get(tableName);
        return direct != null
            ? direct
            : entitiesByTable.getOrDefault(tableName.toLowerCase(Locale.ROOT), Set.of());
    }

    /** JPQL 이 쓰는 엔티티명으로 엔티티를 찾습니다. */
    public Optional<String> entityByName(String jpqlEntityName) {
        return Optional.ofNullable(entityByJpqlName.get(jpqlEntityName));
    }

    /**
     * 이 엔티티의 특정 필드가 가리키는 연관 대상 엔티티입니다.
     *
     * <p>연관 어노테이션이 있는 필드만 봅니다. JPQL 의 {@code JOIN} 대상은 항상 실제 연관이어야
     * 하므로(그렇지 않으면 JPA 구현체가 쿼리 자체를 거부합니다) 유효한 JPQL 을 놓치지
     * 않으면서도, 연관이 아닌 필드가 우연히 엔티티 타입이어서 잘못 연관으로 잡히는 경우를
     * 막습니다.
     */
    public Set<String> associationTargets(String entityInternalName, String fieldName) {
        List<String> hierarchy = new ArrayList<>();
        hierarchy.add(entityInternalName);
        hierarchy.addAll(classes.supertypesOf(entityInternalName));
        for (String current : hierarchy) {
            Optional<Set<String>> found = classes.facts(current)
                .flatMap(facts -> facts.fields().stream()
                    .filter(field -> field.name().equals(fieldName) && isAssociation(field))
                    .findFirst())
                .map(this::associationTargets)
                .filter(targets -> !targets.isEmpty());
            if (found.isPresent()) {
                return found.get();
            }
        }
        return Set.of();
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
        List<MethodFacts> candidates = facts.methods().stream()
            .filter(method -> !method.isStatic())
            .filter(method -> !method.isConstructor())
            .toList();

        Set<String> mutators = new LinkedHashSet<>(inheritedMutators);

        // 엔티티이거나 @MappedSuperclass 여야 이 클래스 자신의 필드 쓰기를 변경자로 셉니다.
        // 상태를 갖지 않는 클래스(어노테이션 없는 중간 클래스 등)의 필드는 영속되지
        // 않으므로 그 클래스의 PUTFIELD 는 세지 않습니다.
        boolean holdsState = isEntity(declaringClass) || facts.hasAnnotation(MAPPED_SUPERCLASS);
        if (holdsState) {
            for (MethodFacts method : candidates) {
                if (!method.writtenOwnFields().isEmpty()) {
                    mutators.add(method.ref().name() + method.ref().descriptor());
                }
            }
        }

        // 다른 변경자를 호출하는 메서드도 변경자입니다. holdsState 와 무관하게 항상
        // 돌립니다 — 상태를 갖지 않는 클래스라도 그 클래스가 선언한 메서드가 상속받은
        // 변경자를 호출할 수 있고(예: @MappedSuperclass 와 @Entity 사이에 낀 평범한
        // 추상 클래스가 상위 타입의 변경자를 부르는 메서드를 선언하는 경우), 대조 집합에
        // 상속받은 변경자가 이미 들어 있으므로 이 호출도 잡아야 합니다. 더 늘지 않을
        // 때까지 반복합니다.
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
     * 테이블명에서 엔티티 집합으로 가는 조회 표를 만듭니다.
     *
     * <p>{@code @Table(name=)} 이 있는 엔티티를 먼저 전부 등록하고, 그 다음에 {@code @Table}
     * 이 없는 엔티티의 기본값을 등록합니다. 두 단계 모두 같은 테이블명 후보에 여러 엔티티가
     * 몰리면 {@link #registerTableName} 이 그 후보 아래에 전부 추가합니다 — 어느 한쪽만
     * 밀어내면 밀린 쪽의 무효화가 통째로 누락되기 때문입니다.
     *
     * <p>두 단계 모두 엔티티 이름을 정렬해서 훑습니다. {@code entities} 는 호출자가 넘긴
     * {@code Set} 의 순회 순서를 그대로 물려받는데, 그 순서는 JVM 기동마다 달라질 수
     * 있습니다. 등록은 순서와 무관하게 합집합이라 최종 결과는 정렬 없이도 같지만, 다른
     * 내부 상태의 재현성을 위해 정렬을 유지합니다.
     */
    private Map<String, Set<String>> buildTableIndex() {
        List<String> sortedEntities = new ArrayList<>(entities);
        Collections.sort(sortedEntities);

        Map<String, Set<String>> index = new LinkedHashMap<>();

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
            // 2. Hibernate 6 계열의 snake_case (Spring Boot 3.x 기본값)
            // 3. Hibernate 7 계열의 snake_case (Spring Boot 4.x 기본값) — 숫자 경계에서
            //    2번과 결과가 갈립니다
            // 4. 순진한 snake_case(대문자마다 밑줄) — 위 규칙들이 못 미치는 경우의 안전망
            String simpleName = MethodRefs.simpleNameOf(entity);
            registerTableName(index, entity, simpleName);
            registerTableName(index, entity, simpleName.toLowerCase(Locale.ROOT));
            registerTableName(index, entity, springPhysicalNamingSnakeCase(simpleName));
            registerTableName(index, entity, hibernate7SnakeCase(simpleName));
            registerTableName(index, entity, camelToSnake(simpleName));
        }

        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : index.entrySet()) {
            // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
            frozen.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        // Map.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        return Collections.unmodifiableMap(new LinkedHashMap<>(frozen));
    }

    private Optional<String> explicitTableNameOf(String entity) {
        return classes.facts(entity)
            .map(facts -> facts.annotation(TABLE))
            .flatMap(values -> values.string("name"))
            .filter(name -> !name.isBlank());
    }

    private static void registerTableName(Map<String, Set<String>> index, String entity, String tableName) {
        index.computeIfAbsent(tableName, key -> new LinkedHashSet<>()).add(entity);
        index.computeIfAbsent(tableName.toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>()).add(entity);
    }

    /**
     * JPQL 엔티티명에서 internal name 으로 가는 조회 표를 만듭니다.
     *
     * <p>기본은 단순 클래스명이지만 {@code @Entity(name = ...)} 로 바꿀 수 있습니다. 이
     * 어노테이션 값이 있어도 단순 클래스명 등록은 그대로 남겨 둡니다 — 두 키가 같은
     * 엔티티를 가리키므로 해가 없고, 지운다고 다른 엔티티와 충돌이 줄어들지도 않습니다.
     *
     * <p>{@link #buildTableIndex} 와 마찬가지로 엔티티 이름을 정렬해서 훑습니다. JPA 는
     * 엔티티명 유일성을 요구하므로 실제로는 두 엔티티가 같은 JPQL 이름을 두고 경쟁할 일이
     * 없어 결과가 갈리지 않지만, 두 색인 구축 메서드의 순회 순서 의존성을 비대칭으로
     * 남겨 두지 않기 위해 맞춥니다.
     */
    private Map<String, String> buildNameIndex() {
        List<String> sortedEntities = new ArrayList<>(entities);
        Collections.sort(sortedEntities);

        Map<String, String> index = new LinkedHashMap<>();
        for (String entity : sortedEntities) {
            index.putIfAbsent(MethodRefs.simpleNameOf(entity), entity);
            classes.facts(entity)
                .map(facts -> facts.annotation(ENTITY))
                .flatMap(values -> values.string("name"))
                .filter(name -> !name.isBlank())
                .ifPresent(name -> index.put(name, entity));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
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

    /**
     * Hibernate 7 의 물리 네이밍 전략({@code PhysicalNamingStrategySnakeCaseImpl}, Spring
     * Boot 4.x 기본값)과 같은 규칙입니다. {@link #springPhysicalNamingSnakeCase}(Hibernate
     * 6 계열, Boot 3.x 기본값)와 앞·뒷 글자 조건이 다릅니다 — 숫자도 소문자처럼 취급해
     * 숫자와 대문자가 맞닿는 자리에 밑줄을 넣습니다.
     *
     * <p>두 세대 jar 의 바이트코드로 직접 대조한 조건입니다.
     * <pre>
     * Hibernate 6.5.3  isLowerCase(before) &amp;&amp; isUpperCase(current) &amp;&amp; isLowerCase(after)
     * Hibernate 7.2.12 (isLowerCase(before) || isDigit(before)) &amp;&amp; isUpperCase(current)
     *                  &amp;&amp; (isLowerCase(after) || isDigit(after))
     * </pre>
     *
     * <p>실측(두 클래스패스에서 Hibernate 가 실제로 만든 테이블): {@code HTTPCache2Entry}
     * 는 Boot 3.3.5 에서 {@code httpcache2entry}, Boot 4.0.6 에서 {@code httpcache2_entry}
     * 입니다. 이 규칙을 후보에 넣지 않으면 Boot 4 쪽 이름이 어떤 후보와도 맞지 않아,
     * 그 테이블을 건드리는 네이티브 SQL 의 엔티티가 조용히 누락됩니다 — 연속된 대문자와
     * 숫자 경계를 함께 가진 이름에서만 갈리지만, 방향이 누락이라 후보를 늘립니다.
     */
    static String hibernate7SnakeCase(String name) {
        StringBuilder builder = new StringBuilder(name);
        for (int i = 1; i < builder.length() - 1; i++) {
            if (isUnderscoreRequiredWithDigits(
                builder.charAt(i - 1), builder.charAt(i), builder.charAt(i + 1))) {
                builder.insert(i++, '_');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isUnderscoreRequired(char before, char current, char after) {
        return Character.isLowerCase(before) && Character.isUpperCase(current) && Character.isLowerCase(after);
    }

    private static boolean isUnderscoreRequiredWithDigits(char before, char current, char after) {
        return (Character.isLowerCase(before) || Character.isDigit(before))
            && Character.isUpperCase(current)
            && (Character.isLowerCase(after) || Character.isDigit(after));
    }
}
