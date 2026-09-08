package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 핸들러 메서드에서 시작해 호출 사슬을 따라가며 만나는 호출 지점을 방문자에게 넘깁니다.
 *
 * <p>사슬이 끊기는 두 자리를 이어붙입니다. 인터페이스 호출은 구현체로, 이벤트 발행은
 * 리스너로 잇습니다.
 */
public final class CallGraphWalker {

    private static final String TRANSACTIONAL =
        "Lorg/springframework/transaction/annotation/Transactional;";
    private static final String PUBLISH_EVENT = "publishEvent";

    private final ClassRepository classes;
    private final ProgramModel program;
    private final ListenerIndex listeners;
    private final List<String> basePackages;
    private final int nodeBudget;

    public CallGraphWalker(ClassRepository classes, ProgramModel program, ListenerIndex listeners,
        List<String> basePackages, int nodeBudget) {
        this.classes = classes;
        this.program = program;
        this.listeners = listeners;
        this.basePackages = List.copyOf(basePackages);
        this.nodeBudget = nodeBudget;
    }

    public WalkResult walk(MethodRef start, WalkVisitor visitor) {
        Set<MethodRef> visited = new HashSet<>();
        List<String> unresolved = new ArrayList<>();
        Deque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(start, false, false));
        boolean budgetExceeded = false;

        while (!pending.isEmpty()) {
            if (visited.size() >= nodeBudget) {
                budgetExceeded = true;
                break;
            }
            Frame frame = pending.pop();
            if (!visited.add(frame.ref())) {
                continue;
            }

            Optional<MethodFacts> maybeFacts = classes.resolveMethod(frame.ref());
            if (maybeFacts.isEmpty()) {
                if (inBasePackages(frame.ref().owner())) {
                    unresolved.add("본문을 읽을 수 없습니다: " + frame.ref());
                }
                continue;
            }
            MethodFacts facts = maybeFacts.get();
            WalkState state = stateFor(facts, frame);

            boolean publishesEvent = false;
            for (MethodRef callee : facts.calls()) {
                visitor.onCall(callee, state);
                if (callee.name().equals(PUBLISH_EVENT)) {
                    publishesEvent = true;
                }
                for (MethodRef target : descendTargets(callee)) {
                    pending.push(new Frame(target, state.inTransaction(), state.readOnlyTransaction()));
                }
            }

            for (MethodRef lambdaBody : facts.lambdaBodies()) {
                if (inBasePackages(lambdaBody.owner())) {
                    pending.push(new Frame(lambdaBody,
                        state.inTransaction(), state.readOnlyTransaction()));
                }
            }

            if (publishesEvent) {
                // 이 메서드 안에서 NEW 로 만든 모든 타입을 이벤트 후보로 봅니다.
                // ApplicationEvent 로 걸러내면 Spring 4.2 이후의 POJO 이벤트를 놓칩니다.
                for (String candidate : facts.newTypes()) {
                    for (MethodRef listener : listeners.listenersFor(candidate)) {
                        // publishEvent 호출은 리스너를 직접 부르지 않으므로 실제 바이트코드
                        // 호출이 없습니다. 리스너 자신을 호출 지점으로 보고하지 않으면, 리스너
                        // 본문이 비어 있는 경우(예: 로깅만 하는 리스너) 이 전이 자체가 방문자에게
                        // 전혀 드러나지 않습니다.
                        visitor.onCall(listener, state);
                        pending.push(new Frame(listener,
                            state.inTransaction(), state.readOnlyTransaction()));
                    }
                }
            }
        }

        return new WalkResult(visited.size(), budgetExceeded, List.copyOf(unresolved));
    }

    /**
     * 이 호출로 내려갈 대상입니다.
     *
     * <p>호출 대상 자신과 구현체를 모두 넣습니다. 인터페이스 여부를 판정하지 않아도 되는 이유는
     * 일반 클래스에서 {@code implementationsOf} 가 빈 집합을 돌려주기 때문입니다.
     */
    private Set<MethodRef> descendTargets(MethodRef callee) {
        if (!inBasePackages(callee.owner())) {
            return Set.of();
        }
        Set<MethodRef> targets = new LinkedHashSet<>();
        targets.add(callee);
        for (String implementation : program.implementationsOf(callee.owner())) {
            targets.add(new MethodRef(implementation, callee.name(), callee.descriptor()));
        }
        return targets;
    }

    /**
     * {@code @Transactional} 을 반영한 새 상태입니다. 메서드 어노테이션이 클래스보다 우선합니다.
     *
     * <p>{@code readOnly} 억제는 사슬 전체가 읽기 전용일 때만 적용합니다. 쓰기 트랜잭션 안의
     * {@code readOnly = true} 는 {@code REQUIRED} 전파로 무시될 수 있으므로 억제하지 않습니다.
     */
    private WalkState stateFor(MethodFacts facts, Frame frame) {
        Optional<AnnotationValues> transactional = transactionalOf(facts);
        if (transactional.isEmpty()) {
            return new WalkState(facts, frame.inTransaction(), frame.readOnly());
        }
        boolean declaredReadOnly = transactional.get().bool("readOnly", false);
        boolean readOnly = declaredReadOnly && (!frame.inTransaction() || frame.readOnly());
        return new WalkState(facts, true, readOnly);
    }

    private Optional<AnnotationValues> transactionalOf(MethodFacts facts) {
        if (facts.hasAnnotation(TRANSACTIONAL)) {
            return Optional.of(facts.annotation(TRANSACTIONAL));
        }
        return classes.facts(facts.ref().owner())
            .filter(classFacts -> classFacts.hasAnnotation(TRANSACTIONAL))
            .map(classFacts -> classFacts.annotation(TRANSACTIONAL));
    }

    private boolean inBasePackages(String internalName) {
        return basePackages.stream().anyMatch(internalName::startsWith);
    }

    private record Frame(MethodRef ref, boolean inTransaction, boolean readOnly) {
    }
}
