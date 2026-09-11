package dev.toktokhan.invalidation.springboot.app;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link NoteEventListener} 본문 안에서만 호출하는 리포지토리입니다. 리스너를 지우면
 * {@link NoteAudit} 쓰기 경로가 통째로 사라집니다.
 */
public interface NoteAuditRepository extends JpaRepository<NoteAudit, Long> {
}
