package io.oryxos;

import io.oryxos.cli.OryxCli;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One composition root; no duplicate Spring Boot app in the library modules. A recognised CLI
 * sub-command (chat, status, ...) runs through Picocli; any other arguments, such as {@code
 * --server.port=8081}, start the web application exactly as before.
 */
@SpringBootApplication
public class OryxOsApplication {
  public static void main(String[] args) {
    if (args.length > 0 && OryxCli.COMMANDS.contains(args[0])) {
      int code = runCli(args);
      if (code != 0) {
        Runtime.getRuntime().exit(code);
      }
      return;
    }
    SpringApplication.run(OryxOsApplication.class, args);
  }

  static int runCli(String[] args) {
    PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    int code = OryxCli.create(new BootCliRuntime(Map.of()), out, err, System.in).execute(args);
    out.flush();
    err.flush();
    return code;
  }
}
