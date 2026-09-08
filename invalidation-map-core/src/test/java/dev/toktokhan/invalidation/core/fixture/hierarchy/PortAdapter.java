package dev.toktokhan.invalidation.core.fixture.hierarchy;

/** 인터페이스에는 본문이 없으므로 resolveMethod 가 구현체를 찾아야 합니다. */
public class PortAdapter implements Port {

    @Override
    public void run() {
    }
}
