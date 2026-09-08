package dev.toktokhan.invalidation.core.fixture.repo;

import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TripJpaRepository
    extends JpaRepository<Trip, Long>, TripRepositoryCustom, ArchivableJpaOperations {

    Optional<Trip> findByTitle(String title);

    long countByTitle(String title);

    /** 이름은 read 로 시작하지만 @Modifying 이 있으므로 쓰기입니다. */
    @Modifying
    @Query("update Trip t set t.title = :title where t.id = :id")
    int readAndRewrite(Long id, String title);

    @Query("select t from Trip t join t.legs l where l.id = :legId")
    List<Trip> findByLeg(Long legId);

    @Query(value = "select * from trip_log", nativeQuery = true)
    List<Trip> findAllNative();
}
