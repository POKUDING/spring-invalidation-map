package dev.toktokhan.invalidation.springboot.app;

import dev.toktokhan.invalidation.core.annotation.InvalidationMapIgnore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notes")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping
    public List<Note> list() {
        return noteService.findAll();
    }

    @PostMapping
    public Note create(@RequestBody NoteRequest request) {
        return noteService.create(request.title());
    }

    @PutMapping("{id}")
    public Note update(@PathVariable Long id, @RequestBody NoteRequest request) {
        return noteService.rename(id, request.title());
    }

    /** 엔티티를 건드리지 않는 헬스체크입니다. 분석 대상에서 뺍니다. */
    @GetMapping("/health")
    @InvalidationMapIgnore
    public String health() {
        return "OK";
    }

    public record NoteRequest(String title) {
    }
}
