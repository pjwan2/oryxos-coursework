package io.oryxos.tool;

import java.util.List;
import java.util.regex.Pattern;

/**
 * How one allowed program may be called. Either any arguments (legacy/operator ceiling) or an
 * explicit list of patterns that EVERY argument must fully match; no pattern, no arguments.
 */
public record CommandRule(boolean anyArguments, List<Pattern> argumentPatterns, int maxArguments) {
  public CommandRule {
    argumentPatterns = List.copyOf(argumentPatterns);
  }

  @Override
  public List<Pattern> argumentPatterns() {
    return List.copyOf(argumentPatterns);
  }

  public static CommandRule unrestricted() {
    return new CommandRule(true, List.of(), Integer.MAX_VALUE);
  }

  /** The program may run, but only without arguments. */
  public static CommandRule noArguments() {
    return new CommandRule(false, List.of(), 0);
  }

  public static CommandRule matching(List<String> regexes, int maxArguments) {
    return new CommandRule(false, regexes.stream().map(Pattern::compile).toList(), maxArguments);
  }

  /** True when every argument is allowed by this rule. */
  public boolean permits(List<String> arguments) {
    if (anyArguments) {
      return true;
    }
    if (arguments.size() > maxArguments) {
      return false;
    }
    for (String argument : arguments) {
      boolean ok = false;
      for (Pattern pattern : argumentPatterns) {
        if (pattern.matcher(argument).matches()) {
          ok = true;
          break;
        }
      }
      if (!ok) {
        return false;
      }
    }
    return true;
  }
}
