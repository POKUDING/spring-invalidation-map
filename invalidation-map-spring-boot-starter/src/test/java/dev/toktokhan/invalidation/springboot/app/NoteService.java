package dev.toktokhan.invalidation.springboot.app;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private static final String DEFAULT_TAG = "created";

    private final NotePort notePort;
    private final NoteJpaRepository notes;
    private final ApplicationEventPublisher events;

    public NoteService(NotePort notePort, NoteJpaRepository notes, ApplicationEventPublisher events) {
        this.notePort = notePort;
        this.notes = notes;
        this.events = events;
    }

    @Transactional
    public Note create(String title) {
        Note note = notePort.create(title);
        events.publishEvent(new NoteCreatedEvent(note.getId(), DEFAULT_TAG));
        return note;
    }

    @Transactional
    public Note rename(Long id, String title) {
        Note note = notes.findById(id).orElseThrow();
        note.rename(title);
        return note;
    }

    public List<Note> findAll() {
        return notes.findAll();
    }
}
