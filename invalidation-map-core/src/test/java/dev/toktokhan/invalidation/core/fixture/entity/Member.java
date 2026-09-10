package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** {@code @Table} 이 없어 기본 테이블명("member")이 {@link Account} 의 명시값과 충돌합니다. */
@Entity
public class Member {

    @Id
    private Long id;
}
