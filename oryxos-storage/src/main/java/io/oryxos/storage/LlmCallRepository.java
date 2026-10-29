package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallRepository extends JpaRepository<LlmCallEntity, Long> {
  List<LlmCallEntity> findBySessionId(String sessionId);
}
