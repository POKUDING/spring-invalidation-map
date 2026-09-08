package dev.toktokhan.invalidation.springboot.app;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link NoteEventListener} 가 이벤트 리스너 본문 안에서 직접 호출하는 리포지토리입니다.
 * {@code Note} 리포지토리와 분리해, 리스너가 붙였다 뗐다 할 수 있는 유일한 {@code NoteTag}
 * 쓰기 경로가 되게 합니다.
 */
public interface NoteTagRepository extends JpaRepository<NoteTag, Long> {
}
