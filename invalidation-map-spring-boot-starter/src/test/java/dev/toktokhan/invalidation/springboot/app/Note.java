package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link dev.toktokhan.invalidation.springboot.SpringProgramModel} 검증용 픽스처 엔티티입니다.
 */
@Entity
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "note_id")
    private List<NoteTag> tags = new ArrayList<>();

    protected Note() {
        // JPA
    }

    public Note(String title) {
        this.title = title;
    }

    public void rename(String title) {
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public List<NoteTag> getTags() {
        return tags;
    }
}
