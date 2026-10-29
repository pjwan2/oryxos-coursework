package io.oryxos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Session messages are serialized as JSON; SQLite is the source of truth. */
@Entity
@Table(name = "sessions")
public class SessionEntity {
  @Id
  @Column(name = "session_id", nullable = false)
  private String id;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(nullable = false)
  private String channel;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "messages_json", nullable = false, columnDefinition = "TEXT")
  private String messagesJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SessionEntity() {}

  public SessionEntity(
      String id, String profileName, String channel, String userId, String messagesJson) {
    this.id = id;
    this.profileName = profileName;
    this.channel = channel;
    this.userId = userId;
    this.messagesJson = messagesJson;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public void update(String newMessagesJson) {
    this.messagesJson = newMessagesJson;
    this.updatedAt = Instant.now();
  }

  public String getId() {
    return id;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getChannel() {
    return channel;
  }

  public String getUserId() {
    return userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }
}
