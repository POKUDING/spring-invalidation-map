package dev.toktokhan.invalidation.springboot.app.extra;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * {@code entity-naming: SIMPLE} 정렬 검증 전용 픽스처 엔티티입니다.
 *
 * <p>{@code SlotInstance}(app 패키지, 연관 필드 없음)와 짝을 이뤄 {@code /sort-check}
 * 엔드포인트에서 함께 읽힙니다. 두 엔티티가 같은 패키지에 있으면 내부 이름(JVM 슬래시 경로)
 * 순서와 단순 클래스명 순서가 항상 같아서, 정렬을 지워도 우연히 같은 결과가 나옵니다(공통
 * 패키지 접두어가 비교에서 상쇄되기 때문입니다). 이 클래스는 단순명이 {@code SlotInstance}
 * 보다 앞서지만({@code "AlphaEntity" < "SlotInstance"}), 내부 이름은 하위 패키지
 * ({@code extra/}, 소문자로 시작)에 있어 {@code SlotInstance} 보다 뒤에 옵니다
 * ({@code "S"} < {@code "e"}, 대문자가 소문자보다 작습니다). 그래서 단순명 정렬 결과
 * ({@code [AlphaEntity, SlotInstance]})와 정렬 전 원래 순서
 * ({@code [SlotInstance, AlphaEntity]})가 실제로 달라, 정렬 호출을 지우면 관찰 가능한
 * 차이가 생깁니다.
 *
 * <p>{@code Note} 를 쓰지 않은 이유는 {@code Note} 가 {@code @OneToMany}/{@code @Embedded}
 * 연관을 가지고 있어({@code NoteTag}, {@code NoteMetadata}) 읽기 집합이 한 단계 더 넓어지고,
 * 그만큼 목록에 원소가 늘어 정렬 검증의 초점이 흐려지기 때문입니다.
 */
@Entity
public class AlphaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;

    protected AlphaEntity() {
        // JPA
    }

    public AlphaEntity(String label) {
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
