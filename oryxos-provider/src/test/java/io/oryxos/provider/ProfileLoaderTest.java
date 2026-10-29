package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileLoaderTest {
  @TempDir Path temp;

  @Test
  void invalidProfileDoesNotPreventValidProfileLoading() throws IOException {
    Files.writeString(
        temp.resolve("valid.yaml"),
        """
                name: weather
                description: Test
                provider: {name: mock, model: test-model, temperature: 0.2}
                tools: [http_get]
                """);
    Files.writeString(
        temp.resolve("bad.yaml"), "name: invalid\nprovider: {name: missing, model: nope}\n");
    ProfileLoader loader = new ProfileLoader(temp, Set.of("mock"));
    assertEquals(1, loader.loadAll().size());
    assertEquals("test-model", loader.loadAll().get("weather").provider().model());
    assertThrows(IllegalArgumentException.class, () -> loader.loadOne(temp.resolve("bad.yaml")));
  }
}
