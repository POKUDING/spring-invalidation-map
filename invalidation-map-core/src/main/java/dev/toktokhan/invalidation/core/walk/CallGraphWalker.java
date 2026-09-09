package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.scan.ClassFacts;
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

    // Spring 의 @Transactional 과 JTA 의 @Transactional 을 모두 트랜잭션 경계로 인정합니다.
    private static final List<String> TRANSACTIONAL_DESCRIPTORS = List.of(
        "Lorg/springframework/transaction/annotation/Transactional;",
        "Ljakarta/transaction/Transactional;");
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
        // 방문 표시는 (메서드, 트랜잭션 상태) 조합인 Frame 단위입니다. ref 만으로 표시하면
        // 같은 메서드를 다른 상태로 두 번 만났을 때 먼저 pop 된 쪽의 상태만 반영되고, 나중에
        // 오는 상태는 통째로 버려집니다 — 예산도 이 Frame 단위 방문 수를 기준으로 삼습니다.
        // 상태 조합이 (inTransaction, readOnly) 4가지뿐이므로 메서드 하나가 최대 4번까지만
        // 재방문되고, 과잉 방향이라 4.4 원칙에도 맞습니다.
        Set<Frame> visited = new HashSet<>();
        // WalkResult.visitedMethods() 는 이름 그대로 서로 다른 메서드 수를 보고합니다.
        Set<MethodRef> visitedMethods = new LinkedHashSet<>();
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
            if (!visited.add(frame)) {
                continue;
            }
            visitedMethods.add(frame.ref());

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
                //
                // 한계: NEW 로 만들지 않은 이벤트(필드에서 꺼내 재발행, 파라미터로 받아 그대로
                // 재발행, 팩터리 메서드가 만들어 돌려준 이벤트)는 newTypes 에 잡히지 않아
                // 후보에 들어오지 않고, 그래서 리스너로 이어지지 않습니다. 팩터리 경로는
                // publishEvent 인자의 디스크립터를 함께 보거나 호출된 팩터리 메서드의
                // newTypes 를 합치는 확장이 필요합니다. 아래에서 candidate 가 하나도 없으면
                // unresolved 에 남겨 이 한계를 드러냅니다.
                if (facts.newTypes().isEmpty()) {
                    unresolved.add("이벤트 타입을 식별하지 못했습니다: " + frame.ref());
                }
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

        return new WalkResult(visitedMethods.size(), budgetExceeded, List.copyOf(unresolved));
    }

    /**
     * 이 호출로 내려갈 대상입니다.
     *
     * <p>호출 대상 자신은 조건 없이 넣습니다 — 이 자리가 진짜로 읽을 수 없으면 그 자체가
     * 미해결 사유입니다. 구현체 후보는 실제로 그 메서드를 갖고 있는 것만 넣되, 이 판정에는
     * 후보의 가독성을 먼저 구분해야 합니다(아래 참고).
     *
     * <p>{@code implementationsOf} 가 인터페이스 하나에 서로 다른 프래그먼트 구현체 여러
     * 개를 돌려줄 수 있고, 그중 일부는 지금 호출하는 메서드를 갖고 있지 않을 수 있습니다
     * (예: 리포지토리 인터페이스 하나에 프래그먼트가 여러 개 걸린 경우, 호출부의 정적
     * 타입이 리포지토리 인터페이스 자체라 프래그먼트 계약을 하나로 좁힐 수 없습니다). 그런
     * 후보를 조건 없이 넣으면 {@code resolveMethod} 가 실패해 "본문을 읽을 수 없습니다" 가
     * 붙는데, 이는 실제로 아무것도 잘못되지 않은 상태를 미해결로 오분류하는 것입니다(실측:
     * 스타터의 {@code SpringProgramModel} 이 리포지토리 인터페이스로 프래그먼트 구현체를
     * 색인할 때 이 모양으로 재현됨).
     *
     * <p>{@code classes.resolveMethod(candidate)} 가 실패하는 이유는 둘로 갈립니다.
     * (1) 후보와 그 상위 타입 사슬 전체를 읽었는데 그 메서드가 없음 — 이 호출과 무관한
     * 후보이므로 조용히 걸러도 4.4 원칙(누락 금지)을 어기지 않습니다. (2) 그 사슬 어딘가를
     * 읽을 수 없음(클래스 바이트를 못 구함, 예외가 아니라 리소스 없음 — 예: {@code
     * SpringProgramModel.classBytes} 가 생성 시점에 고정한 클래스로더가 핫리로드·멀티
     * 클래스로더 환경에서 실제 구현체를 로드한 클래스로더와 달라지는 경우) — 이 경우는
     * 후보가 무관한지 아닌지 판단할 수 없으므로 걸러내면 안 됩니다.
     *
     * <p>이 둘을 구분하려면 {@code resolveMethod} 가 실제로 훑는 범위(후보 자신 +
     * {@code supertypesOf} 전체) 전부가 읽혔는지를 봐야 합니다. 후보 자신의 가독성만
     * 확인하는 것으로는 부족합니다 — 후보 자신은 읽히는데 그 메서드가 선언된 상위 클래스를
     * 못 읽는 경우를 놓치기 때문입니다(실측: 이 부족한 확인이 자신을 못 읽는 경우만 막고
     * 상위 타입을 못 읽는 경우는 그대로 두어, 재검토가 세 번째 트리거로 같은 조용한 누락을
     * 재현했습니다). {@link ClassRepository#isFullyReadable} 이 정확히 이 범위(후보 자신과
     * 상위 타입 사슬 전체)를 확인하므로, {@code resolveMethod} 가 실패했을 때 이것으로
     * "안전하게 없음"과 "못 읽어서 모름"을 구분합니다. 호출 대상 자신(위에서 무조건 추가)은
     * 인터페이스의 추상 선언이라 두 경우 모두 문제없이 resolve 되어 아무 것도 보고하지
     * 않으므로, "호출 대상 자신이 진짜 미해결을 보고한다"는 방어에 기댈 수 없습니다 — 후보
     * 스스로 {@code targets} 에 넣어 {@code walk()} 의 프레임 단위 검사가 미해결로 잡게
     * 해야 합니다.
     */
    private Set<MethodRef> descendTargets(MethodRef callee) {
        if (!inBasePackages(callee.owner())) {
            return Set.of();
        }
        Set<MethodRef> targets = new LinkedHashSet<>();
        targets.add(callee);
        for (String implementation : program.implementationsOf(callee.owner())) {
            MethodRef candidate = new MethodRef(implementation, callee.name(), callee.descriptor());
            // 사슬 전체(후보 자신 + 상위 타입 전부)를 못 읽으면 걸러내지 않고 그대로
            // 넣습니다. 사슬 전체를 읽었는데 메서드가 없는 경우만 걸러냅니다.
            if (classes.resolveMethod(candidate).isPresent() || !classes.isFullyReadable(implementation)) {
                targets.add(candidate);
            }
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
        Optional<AnnotationValues> transactional = transactionalOf(facts, frame);
        if (transactional.isEmpty()) {
            return new WalkState(facts, frame.inTransaction(), frame.readOnly());
        }
        boolean declaredReadOnly = transactional.get().bool("readOnly", false);
        boolean readOnly = declaredReadOnly && (!frame.inTransaction() || frame.readOnly());
        return new WalkState(facts, true, readOnly);
    }

    /**
     * 이 호출 지점에 적용되는 {@code @Transactional} 입니다. 메서드 어노테이션이 있으면
     * 그것을 씁니다.
     *
     * <p>없으면 클래스 레벨 어노테이션을 찾되, {@code facts.ref().owner()}(선언 클래스)가
     * 아니라 {@code frame.ref().owner()}(수신 타입)에서 시작해 그 상위 타입을 따라 올라갑니다.
     * {@code facts} 는 {@code resolveMethod} 의 결과라서, 메서드가 상위 타입에 선언돼 있으면
     * {@code facts.ref().owner()} 는 상위 타입이 됩니다. 그 상위 타입에 어노테이션이 없어도
     * 실제 호출의 수신 타입(하위 클래스)에는 있을 수 있으므로, 수신 타입에서부터 찾아야
     * 놓치지 않습니다.
     */
    private Optional<AnnotationValues> transactionalOf(MethodFacts facts, Frame frame) {
        Optional<AnnotationValues> onMethod = transactionalAnnotationOf(facts);
        if (onMethod.isPresent()) {
            return onMethod;
        }
        List<String> chain = new ArrayList<>();
        chain.add(frame.ref().owner());
        chain.addAll(classes.supertypesOf(frame.ref().owner()));
        for (String current : chain) {
            Optional<AnnotationValues> found = classes.facts(current).flatMap(this::transactionalAnnotationOf);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<AnnotationValues> transactionalAnnotationOf(MethodFacts facts) {
        for (String descriptor : TRANSACTIONAL_DESCRIPTORS) {
            if (facts.hasAnnotation(descriptor)) {
                return Optional.of(facts.annotation(descriptor));
            }
        }
        return Optional.empty();
    }

    private Optional<AnnotationValues> transactionalAnnotationOf(ClassFacts classFacts) {
        for (String descriptor : TRANSACTIONAL_DESCRIPTORS) {
            if (classFacts.hasAnnotation(descriptor)) {
                return Optional.of(classFacts.annotation(descriptor));
            }
        }
        return Optional.empty();
    }

    /**
     * 접두사 비교이되 경로 경계를 확인합니다. {@code com/example} 이 {@code com/exampleother}
     * 처럼 우연히 이어지는 이름까지 걸지 않도록, 접두사 다음 문자가 {@code /} 이거나 정확히
     * 같아야 합니다.
     */
    private boolean inBasePackages(String internalName) {
        return basePackages.stream().anyMatch(basePackage ->
            internalName.equals(basePackage) || internalName.startsWith(basePackage + "/"));
    }

    private record Frame(MethodRef ref, boolean inTransaction, boolean readOnly) {
    }
}
