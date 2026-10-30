package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.memory.AgentMemories;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryIsolationUnsupportedException;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/**
 * One agent's long-term memory is not another's. Session isolation is a different thing (it only
 * separates conversations); these tests are about the memory itself, for both backends that can
 * keep agents apart, through the real tools and prompt loader.
 */
class PerAgentMemoryIsolationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-04T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path temp;

  private Path agents;
  private Set<String> directoryAgents;
  private LongTermMemoryStore global;

  private static AgentProfile profile(String name) {
    return new AgentProfile(
        name,
        "d",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.0),
        List.of("save_memory", "recall_memory"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }

  private static ToolContext as(String agent) {
    return new ToolContext("api:u:" + agent, profile(agent));
  }

  @BeforeEach
  void setUp() throws IOException {
    agents = Files.createDirectories(temp.resolve("agents"));
    directoryAgents = new HashSet<>(Set.of("alpha", "beta"));
    for (String name : directoryAgents) {
      Files.createDirectories(agents.resolve(name));
    }
    global = new MarkdownMemoryStore(temp.resolve("global/MEMORY.md"), CLOCK);
  }

  private PerAgentMemories markdown() {
    return new PerAgentMemories(
        global,
        name ->
            directoryAgents.contains(name) ? Optional.of(agents.resolve(name)) : Optional.empty(),
        (name, home) -> new MarkdownMemoryStore(home.resolve("MEMORY.md"), CLOCK));
  }

  private SQLiteDataSource database() throws SQLException {
    SQLiteDataSource ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + temp.resolve("m.db").toString().replace('\\', '/'));
    try (Connection c = ds.getConnection()) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));
    }
    return ds;
  }

  private PerAgentMemories sqlite(SQLiteDataSource ds) {
    return new PerAgentMemories(
        new SqliteMemoryStore(ds, CLOCK),
        name ->
            directoryAgents.contains(name) ? Optional.of(agents.resolve(name)) : Optional.empty(),
        (name, home) -> new SqliteMemoryStore(ds, CLOCK, name));
  }

  private static List<String> contents(List<MemoryEntry> entries) {
    return entries.stream().map(MemoryEntry::content).toList();
  }

  @Test
  void markdownAgentAFactIsInvisibleToAgentBThroughTheToolsAndThePrompt() {
    MemoryServices services = MemoryServices.perAgent(markdown(), MemoryService.Limits.DEFAULT);
    SaveMemoryTool save = new SaveMemoryTool(services);
    RecallMemoryTool recall = new RecallMemoryTool(services);
    MemoryContextLoader prompt = new MemoryContextLoader(services);

    assertTrue(save.execute("{\"content\":\"alpha likes espresso\"}", as("alpha")).success());
    assertTrue(
        save.execute("{\"content\":\"beta likes tea\",\"scope\":\"CORE\"}", as("beta")).success());

    assertTrue(
        recall.execute("{\"keyword\":\"espresso\"}", as("alpha")).content().contains("espresso"));
    assertEquals(
        "No matching archived memories",
        recall.execute("{\"keyword\":\"espresso\"}", as("beta")).content(),
        "B cannot search A's memory");
    String alphaPrompt = prompt.load(profile("alpha"), new AgentSession("s", "alpha"));
    String betaPrompt = prompt.load(profile("beta"), new AgentSession("s", "beta"));
    assertTrue(
        alphaPrompt.contains("alpha likes espresso") && !alphaPrompt.contains("beta likes tea"));
    assertTrue(
        betaPrompt.contains("beta likes tea") && !betaPrompt.contains("alpha likes espresso"));
  }

  @Test
  void eachMarkdownAgentWritesItsOwnFileInsideItsOwnDirectory() throws IOException {
    PerAgentMemories memories = markdown();
    memories.forAgent("alpha").save(MemoryScope.CORE, "A-FACT");
    memories.forAgent("beta").save(MemoryScope.ARCHIVAL, "B-FACT");

    String alphaFile = Files.readString(agents.resolve("alpha/MEMORY.md"));
    String betaFile = Files.readString(agents.resolve("beta/MEMORY.md"));
    assertTrue(alphaFile.contains("A-FACT") && !alphaFile.contains("B-FACT"));
    assertTrue(betaFile.contains("B-FACT") && !betaFile.contains("A-FACT"));
    assertFalse(
        Files.exists(temp.resolve("global/MEMORY.md")), "the global file was never touched");
  }

  @Test
  void aNewSessionOfTheSameAgentStillReadsItsOwnLongTermMemory() {
    MemoryServices first = MemoryServices.perAgent(markdown(), MemoryService.Limits.DEFAULT);
    new SaveMemoryTool(first)
        .execute("{\"content\":\"remember me\",\"scope\":\"CORE\"}", as("alpha"));

    // A fresh object graph, as after a restart or in another session.
    MemoryServices later = MemoryServices.perAgent(markdown(), MemoryService.Limits.DEFAULT);
    String prompt =
        new MemoryContextLoader(later)
            .load(profile("alpha"), new AgentSession("other-session", "alpha"));

    assertTrue(prompt.contains("remember me"));
  }

  @Test
  void aLegacyAgentKeepsTheSharedGlobalMemoryAndNothingIsCopiedIntoPrivateOnes() {
    global.save(MemoryScope.CORE, "OLD-GLOBAL-FACT");
    PerAgentMemories memories = markdown();

    assertFalse(memories.isolated("legacy-one"));
    assertSame(global, memories.forAgent("legacy-one"));
    assertTrue(contents(memories.forAgent("legacy-one").readCore()).contains("OLD-GLOBAL-FACT"));

    assertTrue(memories.isolated("alpha"));
    assertEquals(
        List.of(), memories.forAgent("alpha").readCore(), "the global memory is not inherited");
    memories.forAgent("alpha").save(MemoryScope.CORE, "ALPHA-ONLY");
    assertEquals(
        List.of("OLD-GLOBAL-FACT"),
        contents(global.readCore()),
        "a private write never reaches the global memory");
  }

  @Test
  void sqliteKeepsPrivateRowsPerAgentAndTheLegacyRowsStayGlobal() throws Exception {
    SQLiteDataSource ds = database();
    PerAgentMemories memories = sqlite(ds);
    memories.forAgent("legacy-one").save(MemoryScope.CORE, "global fact");
    memories.forAgent("alpha").save(MemoryScope.CORE, "alpha core");
    memories.forAgent("alpha").save(MemoryScope.ARCHIVAL, "alpha archival espresso");
    memories.forAgent("beta").save(MemoryScope.ARCHIVAL, "beta archival tea");

    assertEquals(List.of("global fact"), contents(memories.forAgent("legacy-one").readCore()));
    assertEquals(List.of("alpha core"), contents(memories.forAgent("alpha").readCore()));
    assertEquals(List.of(), memories.forAgent("beta").readCore());
    assertEquals(
        List.of("alpha archival espresso"),
        contents(memories.forAgent("alpha").searchArchival("espresso", 10)));
    assertEquals(List.of(), memories.forAgent("beta").searchArchival("espresso", 10));
    assertEquals(
        List.of("beta archival tea"), contents(memories.forAgent("beta").readArchival(10)));
    assertEquals(List.of(), memories.forAgent("legacy-one").readArchival(10));
  }

  @Test
  void sqliteRowsOfOneAgentSurviveAReopenedDatabaseAndStayPrivate() throws Exception {
    SQLiteDataSource ds = database();
    sqlite(ds).forAgent("alpha").save(MemoryScope.CORE, "survives");

    SQLiteDataSource again = new SQLiteDataSource();
    again.setUrl(ds.getUrl());
    PerAgentMemories reopened = sqlite(again);

    assertEquals(List.of("survives"), contents(reopened.forAgent("alpha").readCore()));
    assertEquals(List.of(), reopened.forAgent("beta").readCore());
  }

  @Test
  void aBackendThatCannotIsolateRefusesInsteadOfSharing() {
    PerAgentMemories mem0Like =
        new PerAgentMemories(
            global,
            name -> Optional.of(agents.resolve(name)),
            (name, home) -> {
              throw new MemoryIsolationUnsupportedException("not supported by mem0");
            });
    MemoryServices services = MemoryServices.perAgent(mem0Like, MemoryService.Limits.DEFAULT);

    ToolOutput out = new SaveMemoryTool(services).execute("{\"content\":\"private\"}", as("alpha"));

    assertFalse(out.success());
    assertTrue(out.errorMessage().contains("not supported by mem0"), out.errorMessage());
    assertEquals(List.of(), global.readArchival(10), "nothing leaked into the shared store");
    assertThrows(MemoryIsolationUnsupportedException.class, () -> mem0Like.forAgent("alpha"));
    assertEquals(
        "",
        new MemoryContextLoader(services).load(profile("alpha"), new AgentSession("s", "alpha")),
        "the prompt degrades to no memory instead of failing the turn");
  }

  @Test
  void storesAreReusedPerAgentSoConcurrentWritersCannotLoseEntries() throws Exception {
    PerAgentMemories memories = markdown();
    assertSame(memories.forAgent("alpha"), memories.forAgent("alpha"));
    assertNotSame(memories.forAgent("alpha"), memories.forAgent("beta"));

    int writers = 8;
    int each = 10;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> futures = new java.util.ArrayList<>();
    for (int w = 0; w < writers; w++) {
      int id = w;
      futures.add(
          pool.submit(
              () -> {
                go.await();
                for (int i = 0; i < each; i++) {
                  memories.forAgent("alpha").save(MemoryScope.ARCHIVAL, "w" + id + "-" + i);
                }
                return null;
              }));
    }
    go.countDown();
    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertEquals(writers * each, memories.forAgent("alpha").readArchival(1000).size());
    assertEquals(List.of(), memories.forAgent("beta").readArchival(10));
  }

  @Test
  void anInvalidAgentNameIsRefusedBeforeAnyPathIsBuilt() {
    PerAgentMemories memories = markdown();
    assertThrows(IllegalArgumentException.class, () -> memories.forAgent("../escape"));
    assertThrows(IllegalArgumentException.class, () -> memories.forAgent("a/b"));
    assertThrows(IllegalArgumentException.class, () -> memories.forAgent(null));
  }

  @Test
  void theAgentMemoriesPortAnswersWhichAgentsAreIsolated() {
    AgentMemories memories = markdown();
    assertTrue(memories.isolated("alpha"));
    assertFalse(memories.isolated("someone-else"));
  }
}
