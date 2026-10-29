package io.oryxos.storage;

import io.oryxos.core.agent.ToolCallAudit;
import io.oryxos.core.agent.ToolCallEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaToolCallAudit implements ToolCallAudit {
  private final ToolInvocationRepository repository;

  public JpaToolCallAudit(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(ToolCallEvent event) {
    repository.save(new ToolInvocationEntity(event));
  }
}
