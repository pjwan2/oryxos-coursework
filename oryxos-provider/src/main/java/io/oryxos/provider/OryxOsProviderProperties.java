package io.oryxos.provider;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oryxos")
public class OryxOsProviderProperties {
  private String profileDirectory = ".oryxos/profiles";
  private String agentsDirectory = ".oryxos/agents";
  private List<ProviderEntry> providers = new ArrayList<>();

  public String getProfileDirectory() {
    return profileDirectory;
  }

  public void setProfileDirectory(String path) {
    this.profileDirectory = path;
  }

  /** Where {@code <name>/AGENT.md} directories live; the only writable source of new agents. */
  public String getAgentsDirectory() {
    return agentsDirectory;
  }

  public void setAgentsDirectory(String path) {
    this.agentsDirectory = path;
  }

  public List<ProviderEntry> getProviders() {
    return List.copyOf(providers);
  }

  public void setProviders(List<ProviderEntry> providers) {
    this.providers = new ArrayList<>(providers);
  }

  public static class ProviderEntry {
    private String name;
    private String type;
    private String model;
    private String baseUrl;
    private String apiKey;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }
  }
}
