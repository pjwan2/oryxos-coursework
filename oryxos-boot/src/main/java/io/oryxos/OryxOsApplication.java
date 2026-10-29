package io.oryxos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** One composition root; no duplicate Spring Boot app in the library modules. */
@SpringBootApplication
public class OryxOsApplication {
  public static void main(String[] args) {
    SpringApplication.run(OryxOsApplication.class, args);
  }
}
