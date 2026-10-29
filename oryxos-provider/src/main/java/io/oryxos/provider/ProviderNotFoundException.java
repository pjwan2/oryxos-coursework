package io.oryxos.provider;

public class ProviderNotFoundException extends IllegalArgumentException {
  public ProviderNotFoundException(String provider) {
    super("Provider is not registered: " + provider);
  }
}
