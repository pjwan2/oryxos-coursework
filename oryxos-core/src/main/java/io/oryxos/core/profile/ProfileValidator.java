package io.oryxos.core.profile;

/**
 * One extra check every Profile must pass before it is registered, whether it came from the startup
 * scan, a copied directory or the REST API. Throws {@link AgentDefinitionException} with a message
 * that tells the author what to fix.
 */
@FunctionalInterface
public interface ProfileValidator {
  void validate(AgentProfile profile);
}
