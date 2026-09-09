package dev.toktokhan.invalidation.core.fixture.scan;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/** ClassFactsReader 가 뽑아야 하는 바이트코드 사실을 모두 담은 픽스처입니다. */
@Table(name = "scan_sample")
public class ScanSample {

    private String name;

    /**
     * 어노테이션 값 중 enum 단일 값({@code fetch}), enum 배열({@code cascade}),
     * 클래스 값({@code targetEntity})을 한 번에 담는 필드입니다. 제네릭 필드라
     * {@code FieldFacts.signature} 도 이 필드로 검증합니다.
     */
    @OneToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        targetEntity = RelatedEntity.class)
    private List<RelatedEntity> related;

    /** PUTFIELD 로 자기 필드에 씁니다. */
    public void rename(String next) {
        this.name = next;
    }

    /**
     * 필드에 담긴 값의 타입이 그 필드를 선언한 타입과 다른 픽스처입니다. {@code GETFIELD
     * related} 의 owner 는 {@code ScanSample} 이고 필드 타입은 {@code java/util/List} 라,
     * 선언 타입만 모으는 구현과 필드 타입까지 모으는 구현이 구분됩니다.
     */
    public int relatedCount() {
        return this.related.size();
    }

    /** LDC 로 실린 클래스 리터럴을 담습니다. */
    public Class<?> relatedType() {
        return RelatedEntity.class;
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

    /** boolean 값({@code readOnly})과 클래스 배열 값({@code rollbackFor})을 담습니다. */
    @Transactional(readOnly = true, rollbackFor = {IllegalStateException.class, IllegalArgumentException.class})
    public void commit() {
    }
}
