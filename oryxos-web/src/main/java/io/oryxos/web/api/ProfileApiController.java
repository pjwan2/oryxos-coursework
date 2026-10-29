package io.oryxos.web.api;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileCatalog;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loaded profiles. Only descriptive fields are returned: notify channels (webhook URLs and tokens),
 * the sandbox paths and the free-form settings stay out of the API on purpose.
 */
@Tag(name = "Profile")
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileApiController {
  private final ProfileCatalog profiles;

  public ProfileApiController(ProfileCatalog profiles) {
    this.profiles = profiles;
  }

  public record ProviderView(String name, String model, double temperature) {}

  public record ProfileView(
      String name,
      String description,
      ProviderView provider,
      List<String> tools,
      List<String> skills,
      List<String> mcpServers,
      int scheduleCount,
      boolean sandboxDeclared) {
    public ProfileView {
      tools = List.copyOf(tools);
      skills = List.copyOf(skills);
      mcpServers = List.copyOf(mcpServers);
    }

    @Override
    public List<String> tools() {
      return List.copyOf(tools);
    }

    @Override
    public List<String> skills() {
      return List.copyOf(skills);
    }

    @Override
    public List<String> mcpServers() {
      return List.copyOf(mcpServers);
    }
  }

  @GetMapping
  public ApiResponse<List<ProfileView>> list() {
    return ApiResponse.ok(profiles.list().stream().map(ProfileApiController::view).toList());
  }

  private static ProfileView view(AgentProfile p) {
    return new ProfileView(
        p.name(),
        p.description(),
        new ProviderView(p.provider().name(), p.provider().model(), p.provider().temperature()),
        p.tools(),
        p.skills(),
        p.mcpServers(),
        p.schedules().size(),
        p.settings().get("sandbox") != null);
  }
}
