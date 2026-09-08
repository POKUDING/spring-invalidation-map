package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * {@link ArchivableRecord}(어노테이션 없음)를 거쳐 {@link BaseRecord}(@MappedSuperclass)를
 * 상속하는 엔티티입니다. {@code booking.archive()} 호출 지점의 owner 는 {@code Booking} 이지만
 * 실제 archive() 본문은 ArchivableRecord 에 있습니다.
 */
@Entity
public class Booking extends ArchivableRecord {

    @Id
    private Long id;
}
