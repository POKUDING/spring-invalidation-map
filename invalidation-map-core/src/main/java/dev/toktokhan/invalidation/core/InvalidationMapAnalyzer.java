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
    private static final List<EntityResolver> RESOLVERS = List.of(
        new JpaRepositoryResolver(),
        new EntityManagerResolver(),
        new QuerydslResolver(),
        new DirtyCheckResolver());

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

        WalkResult walk = walker.walk(handler, (callee, state) -> {
            ResolutionContext context = new WalkResolutionContext(
                classes, entities, repositories, state);
            for (EntityResolver resolver : RESOLVERS) {
                Optional<EntityAccess> access = resolver.resolve(callee, context);
                if (access.isPresent()) {
                    EntityAccess found = access.get();
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
                return;
            }
            if (values.bool("override", false)) {
                target.clear();
            }
            target.addAll(declared);
        });
    }

    /**
     * 핸들러 자신과 상위 타입에서 같은 이름·디스크립터 메서드의 어노테이션을 찾습니다.
     *
     * <p>문서 어노테이션을 인터페이스에 붙이는 프로젝트를 지원하기 위함입니다. 코어는 Spring 의
     * {@code AnnotatedElementUtils} 를 쓸 수 없으므로 직접 올라갑니다.
     */
    private Optional<AnnotationValues> annotationOn(ClassRepository classes, MethodRef handler,
        String annotationDescriptor) {
        List<String> candidates = new ArrayList<>();
        candidates.add(handler.owner());
        candidates.addAll(classes.supertypesOf(handler.owner()));
        for (String owner : candidates) {
            Optional<AnnotationValues> found = classes
                .methodFacts(new MethodRef(owner, handler.name(), handler.descriptor()))
                .map(facts -> facts.annotations().get(annotationDescriptor));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
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
