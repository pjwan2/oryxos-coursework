package io.oryxos.core.profile;

/**
 * An agent was archived, not deleted; {@code location} is where its directory (and memory) went.
 */
public record ArchiveResult(String name, String location) {}
