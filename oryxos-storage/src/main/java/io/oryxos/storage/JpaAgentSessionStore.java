package io.oryxos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.SessionIds;
import io.oryxos.core.agent.SessionSummary;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted conversation history. No LLM/network call is inside a DB transaction. */
@Service
public class JpaAgentSessionStore implements AgentSessionStore {
  private static final Logger LOG = LoggerFactory.getLogger(JpaAgentSessionStore.class);
  private static final int PREVIEW_CHARS = 80;

  private final SessionRepository repository;
  private final ObjectMapper mapper;

  public JpaAgentSessionStore(SessionRepository repository, ObjectMapper mapper) {
    this.repository = repository;
    this.mapper = mapper.copy();
  }

  @Override
  @Transactional(readOnly = true)
  public AgentSession loadOrCreate(String channel, String userId, String profileName) {
    String id = SessionIds.of(channel, userId, profileName);
    return find(id).orElseGet(() -> new AgentSession(id, profileName));
  }

  @Override
  @Transactional
  public void save(AgentSession session) {
    String[] keys = session.id().split(":", -1);
    if (keys.length != 3 || !keys[2].equals(session.profileName())) {
      throw new IllegalArgumentException("Invalid session ID / profile");
    }
    String json;
    try {
      json = mapper.writeValueAsString(session.transcript());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialize session", e);
    }
    SessionEntity entity =
        repository
            .findById(session.id())
            .orElseGet(
                () ->
                    new SessionEntity(session.id(), session.profileName(), keys[0], keys[1], json));
    entity.update(json);
    repository.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SessionSummary> list(int limit) {
    if (limit < 1) {
      return List.of();
    }
    return repository.findAllByOrderByUpdatedAtDescIdAsc(PageRequest.of(0, limit)).stream()
        .map(this::summarize)
        .toList();
  }

  @Override
  @Transactional
  public boolean discard(String sessionId) {
    if (!repository.existsById(sessionId)) {
      return false;
    }
    repository.deleteById(sessionId);
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public long count() {
    return repository.count();
  }

  /** A damaged transcript must not hide the other sessions, so it is listed with no preview. */
  private SessionSummary summarize(SessionEntity entity) {
    int count = 0;
    String preview = null;
    try {
      JsonNode messages = mapper.readTree(entity.getMessagesJson());
      count = messages.size();
      for (int i = count - 1; i >= 0 && preview == null; i--) {
        JsonNode message = messages.get(i);
        if ("USER".equals(message.path("role").asText())) {
          preview = abbreviate(message.path("content").asText());
        }
      }
    } catch (JsonProcessingException e) {
      LOG.warn("Session {} has an unreadable transcript", entity.getId());
    }
    return new SessionSummary(
        entity.getId(),
        entity.getProfileName(),
        entity.getChannel(),
        entity.getUserId(),
        count,
        preview,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static String abbreviate(String text) {
    String flat = text.replaceAll("\s+", " ").strip();
    return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AgentSession> find(String sessionId) {
    return repository
        .findById(sessionId)
        .map(
            entity -> {
              try {
                List<AgentMessage> transcript =
                    mapper.readValue(
                        entity.getMessagesJson(), new TypeReference<List<AgentMessage>>() {});
                return AgentSession.restore(entity.getId(), entity.getProfileName(), transcript);
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unable to deserialize session", e);
              }
            });
  }
}
