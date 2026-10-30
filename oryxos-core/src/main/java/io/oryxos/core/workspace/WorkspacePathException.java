package io.oryxos.core.workspace;

/** The path or file type is not allowed in the workspace; the text says why, without echoing it. */
public class WorkspacePathException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public WorkspacePathException(String message) {
    super(message);
  }
}
