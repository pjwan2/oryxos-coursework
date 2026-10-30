package io.oryxos.core.workspace;

/** One line of the workspace tree; {@code path} is relative to the agents directory, with '/'. */
public record WorkspaceEntry(String path, boolean directory, long size) {}
