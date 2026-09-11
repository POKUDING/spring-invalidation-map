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

    /** Spring Data 프래그먼트와 네이티브 SQL 을 거쳐 {@code Note} 를 갱신하는 엔드포인트입니다. */
    @PutMapping("{id}/upsert")
    public void upsert(@PathVariable Long id, @RequestBody NoteRequest request) {
        noteService.upsert(id, request.title());
    }

    /** 엔티티를 건드리지 않는 헬스체크입니다. 분석 대상에서 뺍니다. */
    @GetMapping("/health")
    @InvalidationMapIgnore
    public String health() {
        return "OK";
    }

    /**
     * 엔티티에 전혀 접근하지 않는, {@code @InvalidationMapIgnore} 도 붙지 않은 엔드포인트입니다.
     * 무시된 엔드포인트({@link #health()})와 달리 분석 대상에는 들어가지만 접근을 하나도
     * 찾지 못해 {@code x-entities-unresolved} 로 표시되어야 합니다 — 확장이 하나도 없는 무시
     * 경로와, {@code x-entities-unresolved} 가 붙는 미해결 경로를 구별하는 데 씁니다.
     */
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    /**
     * 핸들러 하나가 경로 두 개에 매핑됩니다. {@code InvalidationMap} 이 경로가 아니라 핸들러
     * 메서드로 키를 잡으므로({@link dev.toktokhan.invalidation.core.InvalidationMap} 참고),
     * 두 경로 모두 완전히 같은 확장을 받아야 합니다.
     */
    @GetMapping({"/multi-a", "/multi-b"})
    public List<Note> multi() {
        return noteService.findAll();
    }

    public record NoteRequest(String title) {
    }
}
