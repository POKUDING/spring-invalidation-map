package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.scan.ClassFacts;
import dev.toktokhan.invalidation.core.scan.ClassFactsReader;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import dev.toktokhan.invalidation.core.scan.SignatureTypeArguments;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProgramModel} 위에 얹은 {@link ClassFacts} 캐시입니다. 타입 계층 질문도 여기서 답합니다.
 *
 * <p>같은 클래스를 여러 엔드포인트가 지나가므로 캐시가 없으면 같은 바이트를 반복해서 읽습니다.
 */
public final class ClassRepository {

    private final ProgramModel program;
    private final Map<String, Optional<ClassFacts>> factsCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> supertypeCache = new ConcurrentHashMap<>();

    public ClassRepository(ProgramModel program) {
        this.program = program;
    }

    public Optional<ClassFacts> facts(String internalName) {
        return factsCache.computeIfAbsent(internalName,
            name -> program.classBytes(name).map(ClassFactsReader::read));
    }

    /** 선언된 그 클래스에서만 찾습니다. */
    public Optional<MethodFacts> methodFacts(MethodRef ref) {
        return facts(ref.owner()).flatMap(classFacts -> classFacts.method(ref));
    }

    /**
     * 선언 클래스에 없으면 상위 타입을 따라 올라가며 같은 이름과 디스크립터의 메서드를 찾습니다.
     *
     * <p>호출 지점은 컴파일 시점의 선언 타입으로 기록되므로, 실제 본문이 상위 클래스에 있는
     * 경우가 있습니다.
     */
    public Optional<MethodFacts> resolveMethod(MethodRef ref) {
        Optional<MethodFacts> direct = methodFacts(ref);
        if (direct.isPresent()) {
            return direct;
        }
        return supertypesOf(ref.owner()).stream()
            .map(supertype -> methodFacts(new MethodRef(supertype, ref.name(), ref.descriptor())))
            .flatMap(Optional::stream)
            .findFirst();
    }

    /** 자신을 제외한 모든 상위 클래스와 인터페이스입니다. 너비 우선 순서입니다. */
    public List<String> supertypesOf(String internalName) {
        return supertypeCache.computeIfAbsent(internalName, start -> {
            List<String> ordered = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!current.equals(start)) {
                    ordered.add(current);
                }
                facts(current).ifPresent(classFacts -> {
                    if (classFacts.superName() != null && seen.add(classFacts.superName())) {
                        queue.add(classFacts.superName());
                    }
                    for (String interfaceName : classFacts.interfaces()) {
                        if (seen.add(interfaceName)) {
                            queue.add(interfaceName);
                        }
                    }
                });
            }
            return List.copyOf(ordered);
        });
    }

    public boolean isSubtypeOf(String internalName, String candidateSupertype) {
        return internalName.equals(candidateSupertype)
            || supertypesOf(internalName).contains(candidateSupertype);
    }

    /**
     * 지정한 상위 타입의 첫 타입 인자를 찾습니다. 상위 타입을 따라 올라가며 찾습니다.
     *
     * <p>{@code QRun extends EntityPathBase<Run>} 에서 {@code Run} 을 얻는 데 씁니다.
     */
    public Optional<String> typeArgumentOfSupertype(String internalName, String supertypeInternalName) {
        List<String> chain = new ArrayList<>();
        chain.add(internalName);
        chain.addAll(supertypesOf(internalName));
        for (String current : chain) {
            Optional<String> found = facts(current)
                .map(classFacts -> SignatureTypeArguments.parse(classFacts.signature()))
                .map(arguments -> arguments.getOrDefault(supertypeInternalName, List.of()))
                .filter(arguments -> !arguments.isEmpty())
                // List.getFirst() 는 Java 21 API 입니다. release = 17 이므로 get(0) 을 씁니다.
                .map(arguments -> arguments.get(0));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
