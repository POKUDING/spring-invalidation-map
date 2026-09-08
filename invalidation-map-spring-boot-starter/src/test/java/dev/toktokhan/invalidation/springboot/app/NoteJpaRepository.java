package dev.toktokhan.invalidation.springboot.app;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteJpaRepository extends JpaRepository<Note, Long>, NoteRepositoryCustom {

    List<Note> findByTitle(String title);
}
