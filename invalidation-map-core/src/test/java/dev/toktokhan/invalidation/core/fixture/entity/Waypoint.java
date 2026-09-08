package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** associationsOf 가 연관 어노테이션이 없는 필드는 연관으로 잡지 않는지 확인하는 데 씁니다. */
@Entity
public class Waypoint {

    @Id
    private Long id;
}
