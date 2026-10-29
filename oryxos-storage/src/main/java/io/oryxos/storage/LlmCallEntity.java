package io.oryxos.storage;

import io.oryxos.provider.LlmCallEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "llm_calls")
public class LlmCallEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String model;

  @Column(name = "prompt_tokens")
  private int promptTokens;

  @Column(name = "completion_tokens")
  private int completionTokens;

  @Column(name = "duration_ms")
  private long durationMs;

  @Column(nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "occurred_at")
  private Instant occurredAt;

  protected LlmCallEntity() {}

  public LlmCallEntity(LlmCallEvent event) {
    this.sessionId = event.sessionId();
    this.provider = event.provider();
    this.model = event.model();
    this.promptTokens = event.promptTokens();
    this.completionTokens = event.completionTokens();
    this.durationMs = event.durationMs();
    this.success = event.success();
    this.errorMessage = event.errorMessage();
    this.occurredAt = event.occurredAt();
  }

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public long getDurationMs() {
    return durationMs;
  }
}
