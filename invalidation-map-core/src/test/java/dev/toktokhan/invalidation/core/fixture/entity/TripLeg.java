package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

/** @Table 이 없으므로 기본 테이블명 규칙이 적용됩니다. */
@Entity
public class TripLeg {

    @Id
    private Long id;

    /** 손자 사슬입니다. cascade 가 걸려 있어 부모를 저장하면 이 엔티티도 실제로 씁니다. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LegPoint> points = new ArrayList<>();
}
