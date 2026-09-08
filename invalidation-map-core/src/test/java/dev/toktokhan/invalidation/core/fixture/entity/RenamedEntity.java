package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * JPQL 에서 클래스명이 아니라 {@code @Entity(name = ...)} 값으로 참조되는 엔티티입니다.
 * {@code EntityIndex.entityByName} 이 클래스명뿐 아니라 이 값도 색인하는지 확인하는 데
 * 씁니다. 이 필드가 없으면 클래스명만 색인해도 기존 테스트가 전부 통과합니다.
 */
@Entity(name = "LegacyBooking")
public class RenamedEntity {

    @Id
    private Long id;
}
