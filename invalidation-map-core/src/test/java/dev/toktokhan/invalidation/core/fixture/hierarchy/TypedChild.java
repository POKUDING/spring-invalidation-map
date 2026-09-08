package dev.toktokhan.invalidation.core.fixture.hierarchy;

/** TypedBase<Payload> 를 상속하므로 시그니처에 타입 인자가 남습니다. */
public class TypedChild extends TypedBase<Payload> {

    @Override
    public Payload load() {
        return new Payload();
    }
}
