package dev.toktokhan.invalidation.core;

import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.resolve.DirtyCheckResolver;
import dev.toktokhan.invalidation.core.resolve.EntityManagerResolver;
import dev.toktokhan.invalidation.core.resolve.EntityResolver;
import dev.toktokhan.invalidation.core.resolve.JpaRepositoryResolver;
import dev.toktokhan.invalidation.core.resolve.QuerydslResolver;
import dev.toktokhan.invalidation.core.resolve.ResolutionContext;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.walk.CallGraphWalker;
import dev.toktokhan.invalidation.core.walk.WalkResult;
import dev.toktokhan.invalidation.core.walk.WalkState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** 엔드포인트를 순회하며 {@link InvalidationMap} 을 만듭니다. 각 층을 여기서 조립합니다. */
public final class InvalidationMapAnalyzer {

    private static final String READS = "Ldev/toktokhan/invalidation/core/annotation/ReadsEntities;";
    private static final String WRITES = "Ldev/toktokhan/invalidation/core/annotation/WritesEntities;";
    private static final String IGNORE =
        "Ldev/toktokhan/invalidation/core/annotation/InvalidationMapIgnore;";

    // 설계 문서 4.2절 순서입니다. 처음 값을 돌려준 리졸버가 이깁니다(first-match-wins) —
    // 순서를 바꾸면 판정이 달라질 수 있습니다.
    //
    // 지금은 네 리졸버가 callee.owner() 기준으로 서로소입니다(리포지토리 / EntityManager
    // 하위 타입 / QueryDSL 패키지·Q클래스 / 엔티티). 그래서 이 순서를 바꿔도 지금 픽스처의
    // 결과는 달라지지 않고, 그 말은 행동 기반 테스트로는 이 순서 계약을 지킬 수 없다는
    // 뜻이기도 합니다. 그래서 resolverOrder() 로 상수 자체를 노출해 테스트가 순서를 직접
    // 단정합니다. **겹치는 owner 를 다루는 리졸버가 추가되면(예: 같은 타입을 리포지토리이자
    // 엔티티로 함께 다루는 리졸버) 이 서로소 성질이 깨지고 순서 의존이 되살아납니다.**
    private static final List<EntityResolver> RESOLVERS = List.of(
        new JpaRepositoryResolver(),
        new EntityManagerResolver(),
        new QuerydslResolver(),
        new DirtyCheckResolver());

    /**
     * 리졸버 체인 순서입니다. 테스트가 이 상수 자체를 단정해 설계 문서 4.2절의 순서를
     * 코드로 지킵니다(위 {@link #RESOLVERS} 주석 참고).
     */
    static List<Class<? extends EntityResolver>> resolverOrder() {
        return RESOLVERS.stream().map(EntityResolver::getClass).toList();
    }

    public InvalidationMap analyze(ProgramModel program, AnalyzerOptions options) {
        ClassRepository classes = new ClassRepository(program);
        EntityIndex entities = new EntityIndex(classes, program.entities());
        RepositoryIndex repositories = new RepositoryIndex(program, classes);
        ListenerIndex listeners = new ListenerIndex(classes, program.eventListeners());
        CallGraphWalker walker = new CallGraphWalker(
            classes, program, listeners, options.basePackages(), options.nodeBudget());

        Map<MethodRef, EndpointEntities> result = new LinkedHashMap<>();
        for (Endpoint endpoint : program.endpoints()) {
            MethodRef handler = endpoint.handler();
            if (result.containsKey(handler)) {
                // 한 핸들러에 경로나 HTTP 메서드가 여러 개 붙은 매핑입니다(InvalidationMap
                // javadoc 참고). 이미 걸었으므로 같은 호출 사슬을 다시 걷지 않습니다.
                continue;
            }
            if (annotationOn(classes, handler, IGNORE).isPresent()) {
                continue;
            }
            result.put(handler, analyzeEndpoint(
                handler, classes, entities, repositories, walker, options));
        }

        // ASM 이 읽지 못한 클래스는 전체에 영향을 주므로 모든 항목에 사유로 붙입니다. 어느
        // 엔드포인트가 그 클래스에 실제로 닿는지 낱낱이 추적하지 않는 대신, 과잉 방향으로
        // 안전하게 전체에 표시합니다(4.4절 원칙).
        Map<String, String> unreadable = classes.unreadableClasses();
        if (!unreadable.isEmpty()) {
            List<String> reasons = unreadable.entrySet().stream()
                .map(entry -> "클래스를 읽지 못했습니다: " + entry.getKey() + " (" + entry.getValue() + ")")
                .sorted()
                .toList();
            result.replaceAll((handler, value) -> withReasons(value, reasons));
        }
        return new InvalidationMap(Collections.unmodifiableMap(new LinkedHashMap<>(result)));
    }

    private EndpointEntities analyzeEndpoint(MethodRef handler, ClassRepository classes,
        EntityIndex entities, RepositoryIndex repositories, CallGraphWalker walker,
        AnalyzerOptions options) {

        Set<String> reads = new TreeSet<>();
        Set<String> writes = new TreeSet<>();
        // 리졸버가 "담당은 맞지만 엔티티를 특정하지 못했다"고 알린 자리입니다. 같은 호출
        // 지점이 트랜잭션 상태마다 다시 방문될 수 있으므로 Set 으로 중복을 지웁니다.
        Set<String> unidentified = new LinkedHashSet<>();

        WalkResult walk = walker.walk(handler, (callee, state) -> {
            ResolutionContext context = new WalkResolutionContext(
                classes, entities, repositories, state);
            for (EntityResolver resolver : RESOLVERS) {
                Optional<EntityAccess> access = resolver.resolve(callee, context);
                if (access.isPresent()) {
                    EntityAccess found = access.get();
                    if (!found.isIdentified()) {
                        // 빈 엔티티 집합을 그냥 더하면 아무 일도 일어나지 않아 조용한 누락이
                        // 됩니다(설계 문서 4.4절). 사유로 남겨 소비자가 이 엔드포인트를
                        // 신뢰할 수 없다는 것을 알게 합니다.
                        unidentified.add("엔티티를 특정하지 못했습니다: " + callee
                            + " (호출한 메서드: " + state.caller().ref() + ")");
                        return;
                    }
                    (found.kind() == AccessKind.WRITE ? writes : reads).addAll(found.entities());
                    return;
                }
            }
        });

        applyAnnotation(classes, handler, READS, reads);
        applyAnnotation(classes, handler, WRITES, writes);

        if (options.expandReadAssociations()) {
            Set<String> expanded = new TreeSet<>(reads);
            for (String entity : reads) {
                expanded.addAll(entities.associationsOf(entity));
            }
            reads.clear();
            reads.addAll(expanded);
        }

        List<String> unresolved = new ArrayList<>(walk.unresolved());
        unresolved.addAll(unidentified);
        if (walk.budgetExceeded()) {
            unresolved.add("호출 사슬이 노드 예산 " + options.nodeBudget() + " 을 넘었습니다");
        }
        if (reads.isEmpty() && writes.isEmpty()) {
            // MyBatis 처럼 이 라이브러리가 모르는 조회 경로만 쓰면 오류 없이 빈 결과가 나옵니다.
            // 표시하지 않으면 조용한 누락이 됩니다. 의도한 경우 @InvalidationMapIgnore 를 붙입니다.
            unresolved.add("엔티티 접근을 찾지 못했습니다");
        }
        unresolved.sort(String::compareTo);

        return new EndpointEntities(
            Collections.unmodifiableSet(new LinkedHashSet<>(reads)),
            Collections.unmodifiableSet(new LinkedHashSet<>(writes)),
            List.copyOf(unresolved));
    }

    /** {@code override = true} 면 대체하고, 아니면 더합니다. */
    private void applyAnnotation(ClassRepository classes, MethodRef handler,
        String annotationDescriptor, Set<String> target) {
        annotationOn(classes, handler, annotationDescriptor).ifPresent(values -> {
            List<String> declared = values.strings("value");
            if (declared.isEmpty()) {
                // value 가 비어 있으면 override 여도 아무것도 하지 않고 그대로 둡니다.
                // "분석 결과가 틀렸으니 비운다" 를 이걸로 선언할 수는 없지만, 과잉 방향이라
                // 4.4 원칙 위반은 아닙니다 — 사라져야 할 엔티티가 남는 것이지, 있어야 할
                // 엔티티가 누락되는 게 아닙니다.
                return;
            }
            if (values.bool("override", false)) {
                target.clear();
            }
            target.addAll(declared);
        });
    }

    /**
     * 핸들러 자신과 상위 타입에서 같은 이름·파라미터의 메서드의 어노테이션을 찾습니다.
     *
     * <p>문서 어노테이션을 인터페이스에 붙이는 프로젝트를 지원하기 위함입니다. 코어는 Spring 의
     * {@code AnnotatedElementUtils} 를 쓸 수 없으므로 직접 올라갑니다.
     *
     * <p>반환 타입은 비교하지 않습니다. 공변 반환 재정의(인터페이스는 {@code Object} 를,
     * 구현은 더 좁은 타입을 반환)는 반환 타입만 달라도 JVM 디스크립터 전체가 달라지므로,
     * 전체 디스크립터로 비교하면 인터페이스의 어노테이션을 놓칩니다(누락 방향이라
     * 4.4 원칙 위반).
     *
     * <p>후보에서 브릿지 메서드({@code MethodFacts.isBridge()})를 뺍니다. 이름과 파라미터가
     * 같고 반환 타입만 다른 메서드 두 개가 "같은 클래스 안에 있을 수 없다" 는 것은 사실이
     * 아닙니다 — 공변 반환 재정의를 컴파일하면 컴파일러가 상위 타입의 소거된 시그니처를
     * 만족시키는 synthetic 브릿지 메서드를 실제 메서드와 함께 만들어, 정확히 그런 쌍이
     * 같은 클래스에 생깁니다. 브릿지를 걸러내지 않으면 어느 쪽이 먼저 뽑히는지가 메서드
     * 테이블의 물리적 순서(컴파일러 구현 세부사항, JVMS 에 순서 보장 없음)에 좌우되어,
     * 개발자가 어노테이션을 공변 반환 재정의 메서드 자신에 붙인 경우 브릿지가 먼저 오는
     * 컴파일러에서는 조용히 누락될 수 있습니다. 브릿지를 빼면 이름·파라미터가 같은 후보는
     * 실제 메서드 하나만 남으므로 어느 쪽을 뽑을지 고민할 필요가 없습니다.
     */
    private Optional<AnnotationValues> annotationOn(ClassRepository classes, MethodRef handler,
        String annotationDescriptor) {
        List<String> candidates = new ArrayList<>();
        candidates.add(handler.owner());
        candidates.addAll(classes.supertypesOf(handler.owner()));
        String parameters = parameterDescriptorOf(handler.descriptor());
        for (String owner : candidates) {
            Optional<AnnotationValues> found = classes.facts(owner)
                .flatMap(classFacts -> classFacts.methods().stream()
                    .filter(method -> !method.isBridge()
                        && method.ref().name().equals(handler.name())
                        && parameterDescriptorOf(method.ref().descriptor()).equals(parameters))
                    .findFirst())
                .map(methodFacts -> methodFacts.annotations().get(annotationDescriptor));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** 디스크립터에서 파라미터 부분만 남깁니다({@code )} 까지). 반환 타입은 버립니다. */
    private static String parameterDescriptorOf(String descriptor) {
        int closingParen = descriptor.indexOf(')');
        return descriptor.substring(0, closingParen + 1);
    }

    private static EndpointEntities withReasons(EndpointEntities value, List<String> reasons) {
        List<String> merged = new ArrayList<>(value.unresolved());
        merged.addAll(reasons);
        merged.sort(String::compareTo);
        return new EndpointEntities(value.reads(), value.writes(), List.copyOf(merged));
    }

    private record WalkResolutionContext(ClassRepository classes, EntityIndex entities,
        RepositoryIndex repositories, WalkState state) implements ResolutionContext {
    }
}
