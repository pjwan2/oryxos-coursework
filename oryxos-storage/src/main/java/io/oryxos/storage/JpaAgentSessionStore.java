package io.oryxos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.SessionIds;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted conversation history. No LLM/network call is inside a DB transaction. */
@Service
public class JpaAgentSessionStore implements AgentSessionStore {
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
