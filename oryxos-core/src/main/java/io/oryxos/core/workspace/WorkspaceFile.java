package io.oryxos.core.workspace;

/** A text file of the workspace; {@code editable} is false for files the API will not write. */
public record WorkspaceFile(String path, String content, long size, boolean editable) {}
