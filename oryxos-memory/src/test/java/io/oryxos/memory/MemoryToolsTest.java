package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import io.oryxos.core.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryToolsTest {
  private static final ToolContext CTX = new ToolContext("s", AgentProfile.example("mock", "m"));
  @TempDir Path temp;
  private LongTermMemoryStore store;
  private SaveMemoryTool save;
  private RecallMemoryTool recall;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-03-04T00:00:00Z"), ZoneOffset.UTC);
    store = new MarkdownMemoryStore(temp.resolve("MEMORY.md"), clock);
    var service = new MemoryService(store, MemoryService.Limits.DEFAULT);
    save = new SaveMemoryTool(service);
    recall = new RecallMemoryTool(service);
  }

  @Test
  void toolsAreRegistrableWithSchemas() {
    var registry = new ToolRegistry(List.of(save, recall));
    assertEquals(List.of("recall_memory", "save_memory"), registry.names());
    assertTrue(save.getInputSchema().contains("\"required\":[\"content\"]"));
  }

  @Test
  void scopeDefaultsToArchival() {
    ToolOutput out = save.execute("{\"content\":\"likes tea\"}", CTX);
    assertTrue(out.success());
    assertEquals(1, store.readArchival(5).size());
    assertTrue(store.readCore().isEmpty());
  }

  @Test
  void coreMustBeRequestedExplicitlyAndIsCaseInsensitive() {
    assertTrue(save.execute("{\"content\":\"name is Alex\",\"scope\":\"CORE\"}", CTX).success());
    assertTrue(save.execute("{\"content\":\"lives in Oslo\",\"scope\":\"core\"}", CTX).success());
    assertTrue(save.execute("{\"content\":\"archived\",\"scope\":\"archival\"}", CTX).success());
    assertEquals(2, store.readCore().size());
    assertEquals(1, store.readArchival(5).size());
  }

  @Test
  void invalidScopeAndBadInputAreFailuresThatWriteNothing() {
    assertFalse(save.execute("{\"content\":\"x\",\"scope\":\"EVERYTHING\"}", CTX).success());
    assertFalse(save.execute("{\"content\":\"x\",\"scope\":5}", CTX).success());
    assertFalse(save.execute("{\"scope\":\"CORE\"}", CTX).success());
    assertFalse(save.execute("{\"content\":\"   \"}", CTX).success());
    assertFalse(save.execute("{\"content\":42}", CTX).success());
    assertFalse(save.execute("not json", CTX).success());
    assertFalse(save.execute("{\"content\":\"" + "x".repeat(2001) + "\"}", CTX).success());
    assertTrue(store.readCore().isEmpty());
    assertTrue(store.readArchival(5).isEmpty());
  }

  @Test
  void recallFindsArchivalOnlyAndReportsNoMatchClearly() {
    store.save(MemoryScope.CORE, "core mentions coffee");
    store.save(MemoryScope.ARCHIVAL, "archive mentions coffee");
    ToolOutput found = recall.execute("{\"keyword\":\"COFFEE\"}", CTX);
    assertTrue(found.success());
    assertEquals("- archive mentions coffee", found.content());
    ToolOutput none = recall.execute("{\"keyword\":\"zebra\"}", CTX);
    assertTrue(none.success());
    assertEquals("No matching archived memories", none.content());
  }

  @Test
  void recallRejectsMissingOrBlankKeyword() {
    assertFalse(recall.execute("{}", CTX).success());
    assertFalse(recall.execute("{\"keyword\":\"  \"}", CTX).success());
    assertFalse(recall.execute("{\"keyword\":7}", CTX).success());
    assertFalse(recall.execute("broken", CTX).success());
  }

  @Test
  void aSavedFactIsRecallableThroughTheToolsAtOnce() {
    save.execute("{\"content\":\"passport expires in June\"}", CTX);
    assertTrue(recall.execute("{\"keyword\":\"passport\"}", CTX).content().contains("June"));
  }

  @Test
  void storageFailureIsARetryableToolFailureWithoutLeakingDetails() {
    LongTermMemoryStore broken =
        new LongTermMemoryStore() {
          public void save(MemoryScope s, String c) {
            throw new MemoryStoreException("/secret/path/disk full");
          }

          public List<MemoryEntry> readCore() {
            throw new MemoryStoreException("/secret/path");
          }

          public List<MemoryEntry> readArchival(int limit) {
            throw new MemoryStoreException("/secret/path");
          }

          public List<MemoryEntry> searchArchival(String k, int limit) {
            throw new MemoryStoreException("/secret/path");
          }
        };
    var service = new MemoryService(broken, MemoryService.Limits.DEFAULT);
    ToolOutput saved = new SaveMemoryTool(service).execute("{\"content\":\"x\"}", CTX);
    ToolOutput recalled = new RecallMemoryTool(service).execute("{\"keyword\":\"x\"}", CTX);
    assertFalse(saved.success());
    assertTrue(saved.retryable());
    assertFalse(saved.errorMessage().contains("/secret"));
    assertFalse(recalled.success());
    assertFalse(recalled.errorMessage().contains("/secret"));
  }
}
