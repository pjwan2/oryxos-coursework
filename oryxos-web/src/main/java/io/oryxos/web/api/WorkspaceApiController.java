package io.oryxos.web.api;

import io.oryxos.core.workspace.WorkspaceEntry;
import io.oryxos.core.workspace.WorkspaceFile;
import io.oryxos.core.workspace.WorkspaceFiles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The files of the registered agents, for the console's file tab. Paths are relative to the agents
 * directory; links, {@code ..}, hidden files, databases, keys and application configuration are
 * refused. Local access only.
 */
@Tag(name = "Workspace")
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceApiController {
  private final WorkspaceFiles workspace;

  public WorkspaceApiController(WorkspaceFiles workspace) {
    this.workspace = workspace;
  }

  public record WriteFile(String path, String content) {}

  @GetMapping("/tree")
  public ApiResponse<List<WorkspaceEntry>> tree(HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(workspace.tree());
  }

  @GetMapping("/file")
  public ApiResponse<WorkspaceFile> read(
      @RequestParam("path") String path, HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(workspace.read(path));
  }

  @PostMapping("/file")
  public ApiResponse<WorkspaceFile> write(@RequestBody WriteFile body, HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null || body.path() == null || body.content() == null) {
      throw new InvalidRequestException("path and content are required");
    }
    return ApiResponse.ok(workspace.save(body.path(), body.content()));
  }
}
