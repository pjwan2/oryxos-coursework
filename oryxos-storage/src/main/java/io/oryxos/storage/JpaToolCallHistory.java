package io.oryxos.storage;

import io.oryxos.core.agent.ToolCallHistory;
import io.oryxos.core.agent.ToolCallRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the same {@code tool_invocations} rows that {@link JpaToolCallAudit} writes. */
@Service
public class JpaToolCallHistory implements ToolCallHistory {
  @PersistenceContext private EntityManager entityManager;

  @Override
  @Transactional(readOnly = true)
  public List<ToolCallRecord> find(String sessionId, Instant since, Instant until, int limit) {
    if (limit < 1) {
      return List.of();
    }
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<ToolInvocationEntity> query = cb.createQuery(ToolInvocationEntity.class);
    Root<ToolInvocationEntity> row = query.from(ToolInvocationEntity.class);
    List<Predicate> filters = new ArrayList<>();
    if (sessionId != null) {
      filters.add(cb.equal(row.<String>get("sessionId"), sessionId));
    }
    if (since != null) {
      filters.add(cb.greaterThanOrEqualTo(row.<Instant>get("occurredAt"), since));
    }
    if (until != null) {
      filters.add(cb.lessThanOrEqualTo(row.<Instant>get("occurredAt"), until));
    }
    query.select(row).where(filters.toArray(Predicate[]::new)).orderBy(cb.desc(row.get("id")));
    return entityManager.createQuery(query).setMaxResults(limit).getResultList().stream()
        .map(JpaToolCallHistory::record)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ToolCallRecord> findByExecution(String executionId, int limit) {
    if (limit < 1 || executionId == null || executionId.isBlank()) {
      return List.of();
    }
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<ToolInvocationEntity> query = cb.createQuery(ToolInvocationEntity.class);
    Root<ToolInvocationEntity> row = query.from(ToolInvocationEntity.class);
    query
        .select(row)
        .where(cb.equal(row.<String>get("executionId"), executionId))
        .orderBy(cb.desc(row.get("id")));
    return entityManager.createQuery(query).setMaxResults(limit).getResultList().stream()
        .map(JpaToolCallHistory::record)
        .toList();
  }

  private static ToolCallRecord record(ToolInvocationEntity e) {
    return new ToolCallRecord(
        e.getId(),
        e.getSessionId(),
        e.getToolName(),
        e.isSuccess(),
        e.getErrorCategory(),
        e.getErrorMessage(),
        e.getDurationMs(),
        e.getOccurredAt(),
        e.getExecutionId());
  }
}
