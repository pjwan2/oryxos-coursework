package io.oryxos.provider;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oryxos")
public class OryxOsProviderProperties {
  private String profileDirectory = ".oryxos/profiles";
  private String agentsDirectory = ".oryxos/agents";
  private String archiveDirectory = ".oryxos/archive";
  private String newAgentProvider = "mock";
  private String newAgentModel = "mock-echo";
  private boolean agentsWatch = true;
  private long agentsWatchDebounceMillis = 500;
  private long agentsReconcileSeconds = 30;
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

  /** Where an archived agent (its directory and memory) is moved; never deleted. */
  public String getArchiveDirectory() {
    return archiveDirectory;
  }

  public void setArchiveDirectory(String path) {
    this.archiveDirectory = path;
  }

  /** The provider and model a newly created agent starts with (it can be changed afterwards). */
  public String getNewAgentProvider() {
    return newAgentProvider;
  }

  public void setNewAgentProvider(String provider) {
    this.newAgentProvider = provider;
  }

  public String getNewAgentModel() {
    return newAgentModel;
  }

  public void setNewAgentModel(String model) {
    this.newAgentModel = model;
  }

  public boolean isAgentsWatch() {
    return agentsWatch;
  }

  public void setAgentsWatch(boolean agentsWatch) {
    this.agentsWatch = agentsWatch;
  }

  public long getAgentsWatchDebounceMillis() {
    return agentsWatchDebounceMillis;
  }

  public void setAgentsWatchDebounceMillis(long millis) {
    this.agentsWatchDebounceMillis = millis;
  }

  public long getAgentsReconcileSeconds() {
    return agentsReconcileSeconds;
  }

  public void setAgentsReconcileSeconds(long seconds) {
    this.agentsReconcileSeconds = seconds;
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
