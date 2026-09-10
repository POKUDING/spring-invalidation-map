package dev.toktokhan.invalidation.core.support;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 직접 합성한 클래스 바이트를 위임 대상 위에 얹는 위임체입니다. 등록한 이름은 합성 바이트를
 * 돌려주고, 나머지는 위임 대상 그대로입니다.
 *
 * <p>테스트 클래스패스에 없는 어노테이션이 붙은 클래스를 분석 대상으로 삼는 데 씁니다.
 * 코어는 어노테이션을 디스크립터 문자열로만 다루므로, 그 어노테이션의 실제 클래스가 없어도
 * ASM 으로 디스크립터를 직접 써 넣으면 같은 경로를 밟을 수 있습니다 — 새 테스트 의존성을
 * 추가하지 않고 JTA {@code @Transactional} 같은 자리를 덮는 방법입니다.
 */
public final class SyntheticClassProgramModel implements ProgramModel {

    private final ProgramModel delegate;
    private final Map<String, byte[]> synthetic;

    public SyntheticClassProgramModel(ProgramModel delegate, Map<String, byte[]> synthetic) {
        this.delegate = delegate;
        this.synthetic = Map.copyOf(synthetic);
    }

    @Override
    public List<Endpoint> endpoints() {
        return delegate.endpoints();
    }

    @Override
    public Optional<byte[]> classBytes(String internalName) {
        byte[] bytes = synthetic.get(internalName);
        return bytes != null ? Optional.of(bytes) : delegate.classBytes(internalName);
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
