package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** @Table 이 없으므로 기본 테이블명 규칙이 적용됩니다. */
@Entity
public class TripLeg {

    @Id
    private Long id;
}
