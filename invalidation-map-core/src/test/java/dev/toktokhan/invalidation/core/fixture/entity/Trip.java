package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
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

    /** 연관 어노테이션이 없는 필드입니다. associationsOf 는 이 필드를 연관으로 잡지 않아야 합니다. */
    private Waypoint nextStop;

    /**
     * 연관 어노테이션은 있지만 대상이 엔티티가 아닌 필드입니다. associationsOf 는 이 필드도
     * 잡지 않아야 합니다.
     */
    @OneToMany
    private List<String> tags = new ArrayList<>();

    /** 자기 자신을 가리키는 연관입니다. associationsOf 는 자기 자신을 결과에서 빼야 합니다. */
    @ManyToOne
    private Trip previousTrip;

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

    /**
     * 전이적 변경자: 상위 타입(BaseRecord)에 선언된 변경자를 호출합니다. 이 호출 지점의
     * owner 는 정적 수신 타입인 Trip 이고, markDeleted 의 본문은 BaseRecord 에 있습니다.
     */
    public void archive() {
        markDeleted();
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
