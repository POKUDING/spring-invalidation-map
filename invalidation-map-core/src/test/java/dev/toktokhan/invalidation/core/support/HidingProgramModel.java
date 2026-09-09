package dev.toktokhan.invalidation.core.support;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 특정 내부 이름의 클래스 바이트만 못 구하는 것처럼 감싸는 위임체입니다. 그 이름을 제외한
 * 나머지는 위임 대상 그대로 돌려줍니다.
 *
 * <p>클래스 사슬(자신 또는 상위 타입)의 특정 한 링크만 클래스로더가 못 찾는 상황(핫리로드·
 * 멀티 클래스로더 환경 등)을 재현하는 데 씁니다.
 */
public final class HidingProgramModel implements ProgramModel {

    private final ProgramModel delegate;
    private final Set<String> hiddenInternalNames;

    public HidingProgramModel(ProgramModel delegate, String... hiddenInternalNames) {
        this.delegate = delegate;
        this.hiddenInternalNames = Set.of(hiddenInternalNames);
    }

    @Override
    public List<Endpoint> endpoints() {
        return delegate.endpoints();
    }

    @Override
    public Optional<byte[]> classBytes(String internalName) {
        return hiddenInternalNames.contains(internalName) ? Optional.empty() : delegate.classBytes(internalName);
    }

    @Override
    public Optional<String> entityFor(String repositoryInternalName) {
        return delegate.entityFor(repositoryInternalName);
    }

    @Override
    public Set<String> implementationsOf(String interfaceInternalName) {
        return delegate.implementationsOf(interfaceInternalName);
    }

    @Override
    public Set<String> entities() {
        return delegate.entities();
    }

    @Override
    public Set<MethodRef> eventListeners() {
        return delegate.eventListeners();
    }
}
