package dev.toktokhan.invalidation.springboot.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code @TransactionalEventListener} 는 {@code @EventListener} 로 메타 어노테이션되어
 * 있으므로 {@code eventListeners()} 가 둘 다 한 번의 검사로 잡아야 합니다.
 */
@Component
public class NoteEventListener {

    private final NoteJpaRepository notes;

    public NoteEventListener(NoteJpaRepository notes) {
        this.notes = notes;
    }

    @TransactionalEventListener
    public void onNoteCreated(NoteCreatedEvent event) {
        notes.findById(event.noteId()).ifPresent(note -> {
            note.getTags().add(new NoteTag(event.label()));
            notes.save(note);
        });
    }
}
