package io.oryxos.storage;

import io.oryxos.provider.LlmCallAudit;
import io.oryxos.provider.LlmCallEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaLlmCallAudit implements LlmCallAudit {
  private final LlmCallRepository repository;

  public JpaLlmCallAudit(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(LlmCallEvent event) {
    repository.save(new LlmCallEntity(event));
  }
}
