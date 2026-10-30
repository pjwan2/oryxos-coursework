package io.oryxos.storage;

import io.oryxos.core.agent.ToolCallEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tool_invocations")
public class ToolInvocationEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tool_name", nullable = false)
  private String toolName;

  @Column(nullable = false)
  private boolean success;

  @Column(name = "error_category")
  private String errorCategory;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "execution_id")
  private String executionId;

  protected ToolInvocationEntity() {}

  public ToolInvocationEntity(ToolCallEvent event) {
    this.sessionId = event.sessionId();
    this.toolName = event.toolName();
    this.success = event.success();
    this.errorCategory = event.errorCategory();
    this.errorMessage = event.errorMessage();
    this.durationMs = event.durationMs();
    this.occurredAt = event.occurredAt();
    this.executionId = event.executionId();
  }

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getToolName() {
    return toolName;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getErrorCategory() {
    return errorCategory;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getExecutionId() {
    return executionId;
  }
}
