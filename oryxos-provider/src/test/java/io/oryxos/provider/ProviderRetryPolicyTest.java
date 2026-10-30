package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Found by the week-5 end-to-end test: with Spring AI's default retry a provider that is down held
 * the request thread for minutes, so "503 within a moment" was not true. The policy is now bounded.
 */
class ProviderRetryPolicyTest {
  @Test
  void aProviderSayingTryAgainIsRetriedThreeTimesInAll() {
    AtomicInteger attempts = new AtomicInteger();
    assertThrows(
        TransientAiException.class,
        () ->
            ProviderConfiguration.boundedRetry()
                .execute(
                    context -> {
                      attempts.incrementAndGet();
                      throw new TransientAiException("429");
                    }));
    assertEquals(3, attempts.get());
  }

  @Test
  void anUnreachableProviderFailsAtOnceWithoutWaiting() {
    AtomicInteger attempts = new AtomicInteger();
    long begin = System.nanoTime();
    assertThrows(
        ResourceAccessException.class,
        () ->
            ProviderConfiguration.boundedRetry()
                .execute(
                    context -> {
                      attempts.incrementAndGet();
                      throw new ResourceAccessException("io", new ConnectException("refused"));
                    }));
    assertEquals(1, attempts.get(), "a refused connection is not retried");
    assertTrue((System.nanoTime() - begin) / 1_000_000 < 250, "and does not pause");
  }

  @Test
  void theWholeRetryBudgetIsSmallComparedWithTheDefaultOfMinutes() {
    long begin = System.nanoTime();
    assertThrows(
        TransientAiException.class,
        () ->
            ProviderConfiguration.boundedRetry()
                .execute(
                    context -> {
                      throw new TransientAiException("503");
                    }));
    assertTrue((System.nanoTime() - begin) / 1_000_000_000 < 5);
  }
}
