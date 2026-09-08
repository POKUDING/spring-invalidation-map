package dev.toktokhan.invalidation.springboot.app;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * pirl-spring 의 {@code SlotInstanceRepository} 와 같은 이름·구조입니다. 리포지토리
 * 인터페이스 이름 규칙(레거시: {@code SlotInstanceRepositoryImpl} 이 {@code SlotInstance}
 * 가 아니라 이 인터페이스 이름 + {@code Impl} 로 지어짐)을 재현합니다 — 프래그먼트 인터페이스
 * 이름 규칙을 쓰는 {@link NoteJpaRepository}/{@link NoteRepositoryCustom} 과 대비됩니다.
 */
public interface SlotInstanceRepository
    extends JpaRepository<SlotInstance, Long>, SlotInstanceRepositoryCustom {
}
