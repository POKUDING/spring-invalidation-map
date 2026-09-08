package dev.toktokhan.invalidation.core.fixture.scan;

import java.util.function.Supplier;

/** ClassFactsReader 가 뽑아야 하는 바이트코드 사실을 모두 담은 픽스처입니다. */
public class ScanSample {

    private String name;

    /** PUTFIELD 로 자기 필드에 씁니다. */
    public void rename(String next) {
        this.name = next;
    }

    /** LDC 문자열 상수와 메서드 호출을 담습니다. */
    public String describe() {
        String prefix = "sample";
        return prefix.concat(name);
    }

    /** NEW 명령을 담습니다. */
    public Object create() {
        return new StringBuilder();
    }

    /** INVOKEDYNAMIC 으로 합성 람다 메서드를 만듭니다. */
    public Supplier<String> lazy() {
        return () -> name;
    }

    /** 런타임 유지 어노테이션이 값과 함께 붙습니다. */
    @Deprecated(since = "1.0")
    public void legacy() {
    }
}
