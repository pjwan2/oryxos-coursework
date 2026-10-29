package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, Long> {
  List<ToolInvocationEntity> findBySessionId(String sessionId);
}
