package io.oryxos.core.profile;

import io.oryxos.core.agent.AgentProfileResolver;
import java.util.List;

/** Read-only view of every loaded profile, for listing; lookup stays on the resolver. */
public interface ProfileCatalog extends AgentProfileResolver {
  /** All loaded profiles ordered by name. */
  List<AgentProfile> list();
}
