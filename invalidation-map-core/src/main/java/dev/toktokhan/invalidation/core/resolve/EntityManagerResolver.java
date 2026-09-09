package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code EntityManager} 호출을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절 3번입니다.
 *
 * <p>쿼리 문자열은 호출 인자이지만 바이트코드에서 스택을 추적하지 않으므로, 호출을 담은
 * 메서드의 문자열 상수 전체를 후보로 씁니다. 한 메서드에 쿼리가 여러 개면 모두 반영되어
 * 과잉이 됩니다. 오차 방향 원칙에 맞습니다.
 *
 * <p><b>{@code persist}/{@code merge}/{@code remove}/{@code find}/{@code getReference} 도
 * 같은 방식으로 다룹니다.</b> {@code persist} 계열의 디스크립터는
 * {@code (Ljava/lang/Object;)V} 이고 {@code find} 계열의 첫 인자는 {@code Class} 리터럴이라,
 * 어느 쪽도 호출 지점의 인자 타입만으로는 엔티티를 알 수 없습니다. 그래서 호출을 담은
 * 메서드에 나타난 엔티티 타입 전체를 후보로 씁니다 — {@code NEW} 로 만든 타입, {@code LDC}
 * 로 실린 클래스 리터럴, 그 메서드 자신의 파라미터 타입, 읽은 필드의 타입입니다. 한
 * 메서드가 엔티티 두 개를 건드리면 둘 다 보고되어 과잉이 됩니다.
 *
 * <p>후보가 하나도 없으면 {@link EntityAccess#unidentified} 를 돌려줍니다. 이전 구현은
 * 빈 {@code Optional} 을 돌려주며 "워커가 본문으로 내려가 실제 접근을 찾는다"를 근거로
 * 삼았지만, 그 근거는 사실이 아닙니다 — {@code CallGraphWalker.descendTargets} 는 기준
 * 패키지 밖 호출로 내려가지 않고 {@code jakarta/persistence/EntityManager} 는 항상 기준
 * 패키지 밖입니다. 즉 그 자리는 프레임이 만들어지지 않아 미해결 사유조차 생기지 않았고,
 * 같은 엔드포인트에 해결된 접근이 하나라도 있으면 {@code em.persist(...)} 의 쓰기가
 * 아무 표시 없이 사라졌습니다. 4.4 원칙이 금지하는 방향이라 여기서 직접 표시합니다.
 *
 * <p>엔티티를 지목하지 않는 호출({@code flush}, {@code clear}, {@code detach},
 * {@code contains}, {@code unwrap} 등)은 담당이 아니므로 빈 {@code Optional} 입니다.
 * 이들을 미해결로 표시하면 {@code EntityManager} 를 쓰는 모든 자리가 소음을 만들고,
 * 그 자리의 엔티티는 이미 그 객체를 얻어 온 조회·저장 호출에서 보고됩니다.
 */
public final class EntityManagerResolver implements EntityResolver {

    private static final String ENTITY_MANAGER = "jakarta/persistence/EntityManager";

    /** 인자로 받은 엔티티 인스턴스를 영속화·삭제하는 호출입니다. */
    private static final Set<String> INSTANCE_WRITES = Set.of("persist", "merge", "remove");

    /** 첫 인자의 {@code Class} 리터럴로 엔티티를 지목해 읽는 호출입니다. */
    private static final Set<String> CLASS_READS = Set.of("find", "getReference");

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (!isEntityManager(callee.owner(), context)) {
            return Optional.empty();
        }
        if (context.state() == null || context.state().caller() == null) {
            return Optional.empty();
        }
        MethodFacts caller = context.state().caller();

        if (INSTANCE_WRITES.contains(callee.name())) {
            return Optional.of(fromTypeCandidates(caller, context, AccessKind.WRITE));
        }
        if (CLASS_READS.contains(callee.name())) {
            return Optional.of(fromTypeCandidates(caller, context, AccessKind.READ));
        }

        boolean nativeQuery = callee.name().equals("createNativeQuery");
        boolean jpql = callee.name().equals("createQuery");
        if (!nativeQuery && !jpql) {
            return Optional.empty();
        }
        return Optional.of(fromQueryStrings(caller, context, nativeQuery));
    }

    /**
     * 호출을 담은 메서드에 나타난 엔티티 타입에서 접근을 만듭니다. 후보가 없으면
     * 특정하지 못했다고 표시합니다.
     */
    private static EntityAccess fromTypeCandidates(MethodFacts caller, ResolutionContext context,
        AccessKind kind) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(caller.newTypes());
        candidates.addAll(caller.classConstants());
        candidates.addAll(MethodRefs.parameterTypesOf(caller.ref().descriptor()));
        candidates.addAll(caller.referencedFieldTypes());

        Set<String> found = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (context.entities().isEntity(candidate)) {
                found.add(candidate);
            }
        }
        if (found.isEmpty()) {
            return EntityAccess.unidentified(kind);
        }
        return new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind);
    }

    /**
     * 호출을 담은 메서드의 문자열 상수에서 접근을 만듭니다. 어느 문자열도 엔티티로 풀리지
     * 않으면 특정하지 못했다고 표시합니다 — Criteria API 를 쓰거나 쿼리 문자열을 다른
     * 곳에서 받아 오면 이 메서드에는 단서가 남지 않습니다. 방향을 알 수 없으므로 읽기로
     * 두되, 특정하지 못했다는 사실 자체가 미해결로 드러납니다.
     */
    private static EntityAccess fromQueryStrings(MethodFacts caller, ResolutionContext context,
        boolean nativeQuery) {
        Set<String> found = new LinkedHashSet<>();
        AccessKind kind = AccessKind.READ;
        boolean matched = false;
        for (String candidate : caller.stringConstants()) {
            Set<String> resolved = nativeQuery
                ? SqlTableExtractor.entities(candidate, context.entities())
                : JpqlEntityExtractor.entities(candidate, context.entities(), context.classes());
            if (resolved.isEmpty()) {
                continue;
            }
            matched = true;
            found.addAll(resolved);
            AccessKind candidateKind = nativeQuery
                ? SqlTableExtractor.kindOf(candidate)
                : JpqlEntityExtractor.kindOf(candidate);
            if (candidateKind == AccessKind.WRITE) {
                kind = AccessKind.WRITE;
            }
        }
        if (!matched) {
            return EntityAccess.unidentified(kind);
        }
        return new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind);
    }

    private static boolean isEntityManager(String owner, ResolutionContext context) {
        return owner.equals(ENTITY_MANAGER) || context.classes().isSubtypeOf(owner, ENTITY_MANAGER);
    }
}
