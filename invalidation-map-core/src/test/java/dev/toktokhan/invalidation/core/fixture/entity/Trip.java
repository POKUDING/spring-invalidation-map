package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trip_log")
public class Trip extends BaseRecord {

    @Id
    private Long id;

    private String title;

    private int distance;

    @OneToMany
    private List<TripLeg> legs = new ArrayList<>();

    @Embedded
    private Coordinate start;

    /** 직접 변경자: 자기 필드에 씁니다. */
    public void rename(String next) {
        this.title = next;
    }

    /** 전이적 변경자: 다른 변경자를 호출합니다. */
    public void reset(String next) {
        rename(next);
        setDistance(0);
    }

    private void setDistance(int next) {
        this.distance = next;
    }

    /** 변경자가 아닙니다: 읽기만 합니다. */
    public String title() {
        return title;
    }

    /** 변경자가 아닙니다: 정적 팩터리는 새 객체를 만듭니다. */
    public static Trip create(String title) {
        Trip trip = new Trip();
        trip.title = title;
        return trip;
    }
}
