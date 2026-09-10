package dev.toktokhan.invalidation.springboot.app;

/**
 * {@link NoteJpaRepository} 가 합성하는 프래그먼트 인터페이스입니다. Spring Data 가 표준
 * CRUD 메서드로 표현할 수 없는 호출(여기서는 네이티브 UPSERT)을 여기에 선언합니다.
 */
public interface NoteRepositoryCustom {

    void upsert(Long id, String title);
}
