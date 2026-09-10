package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * 테이블명 파생 규칙의 세대차를 드러내는 픽스처 엔티티입니다. {@code @Table} 을 붙이지
 * 않아 Hibernate 의 물리 네이밍 전략이 그대로 적용됩니다.
 *
 * <p>이름에 두 가지 특징을 함께 넣었습니다.
 * <ul>
 *   <li>연속된 대문자({@code HTTP}) — 여기에는 어느 세대도 밑줄을 넣지 않습니다
 *   <li>숫자 바로 뒤의 대문자({@code 2E}) — Hibernate 6 은 밑줄을 넣지 않고
 *       Hibernate 7 은 넣습니다
 * </ul>
 *
 * <p>그래서 실제 테이블명이 세대마다 달라집니다. 이 픽스처를 쓰는 테스트는 어느 쪽 규칙도
 * 코드에 적지 않고, Hibernate 가 실제로 만든 테이블 이름을 데이터베이스에서 읽어
 * {@code EntityIndex} 가 그 이름을 이 엔티티로 되돌릴 수 있는지만 확인합니다.
 */
@Entity
public class HTTPCache2Entry {

    @Id
    @GeneratedValue
    private Long id;

    private String payload;

    public Long getId() {
        return id;
    }

    public String getPayload() {
        return payload;
    }
}
