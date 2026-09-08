package dev.toktokhan.invalidation.springboot.app;

import org.springframework.stereotype.Repository;

/**
 * {@link NotePort} 를 구현하는 빈입니다. {@link NoteJpaRepository} 를 호출합니다.
 */
@Repository
public class NotePortAdapter implements NotePort {

    private final NoteJpaRepository notes;

    public NotePortAdapter(NoteJpaRepository notes) {
        this.notes = notes;
    }

    @Override
    public Note create(String title) {
        return notes.save(new Note(title));
    }
}
