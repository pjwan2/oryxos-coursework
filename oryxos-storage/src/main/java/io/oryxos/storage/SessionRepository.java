package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
  /** Most recently active first; the id breaks ties so the order is stable. */
  List<SessionEntity> findAllByOrderByUpdatedAtDescIdAsc(Pageable page);
}
