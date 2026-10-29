package io.oryxos.storage.migration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Upgrades the database while the application context is being built, before any bean that reads it
 * and therefore before the web server or the scheduler can serve anything. A failure aborts the
 * context, so the process exits instead of running against a half-migrated schema.
 */
@Configuration
public class DatabaseMigrationConfiguration {
  /** Beans that touch the database while they are being constructed or initialised. */
  private static final List<String> DATABASE_USERS =
      List.of("longTermMemoryStore", "jdbcScheduledTaskStore");

  @Bean
  public MigrationReport databaseMigration(
      DataSource dataSource,
      @Value("${oryxos.database.backup-before-migrate:true}") boolean backupBeforeMigrate) {
    return new SqliteMigrator(
            Migrations.ALL,
            Migrations.FRESH_SCHEMA_RESOURCE,
            backupBeforeMigrate,
            Clock.systemUTC())
        .migrate(dataSource);
  }

  /** Makes the JPA layer and the JDBC stores wait for {@link #databaseMigration}. */
  @Bean
  public static BeanFactoryPostProcessor databaseMigrationComesFirst() {
    return (ConfigurableListableBeanFactory beanFactory) -> {
      List<String> dependents = new ArrayList<>();
      for (Class<?> type :
          new Class<?>[] {
            LocalContainerEntityManagerFactoryBean.class, EntityManagerFactory.class
          }) {
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
          dependents.add(name.startsWith("&") ? name.substring(1) : name);
        }
      }
      for (String name : DATABASE_USERS) {
        if (beanFactory.containsBeanDefinition(name)) {
          dependents.add(name);
        }
      }
      for (String name : dependents.stream().distinct().toList()) {
        if (!beanFactory.containsBeanDefinition(name)) {
          continue;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        List<String> existing = new ArrayList<>();
        if (definition.getDependsOn() != null) {
          existing.addAll(Arrays.asList(definition.getDependsOn()));
        }
        if (!existing.contains("databaseMigration")) {
          existing.add("databaseMigration");
          definition.setDependsOn(existing.toArray(String[]::new));
        }
      }
    };
  }
}
