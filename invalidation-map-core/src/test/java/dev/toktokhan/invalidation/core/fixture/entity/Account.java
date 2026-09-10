package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 다른 엔티티({@link Member})의 기본 테이블명과 같은 이름을 명시적으로 선언한
 * 엔티티입니다. {@code @Table} 값은 개발자가 선언한 사실이므로 다른 엔티티의 추정된
 * 기본값보다 항상 우선해야 합니다.
 */
@Entity
@Table(name = "member")
public class Account {

    @Id
    private Long id;
}
