package interview.guide.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Validates that POSTGRES_PASSWORD is set when using a PostgreSQL datasource.
 * Runs as an EnvironmentPostProcessor, before ApplicationContext refresh
 * and before DataSource/Flyway initialization.
 */
public class PostgresPasswordValidator implements EnvironmentPostProcessor, Ordered {

  private static final String PG_URL_PREFIX = "jdbc:postgresql:";
  private static final String DS_URL_KEY = "spring.datasource.url";
  private static final String PW_KEY = "POSTGRES_PASSWORD";
  private static final String ERROR_MESSAGE =
    "POSTGRES_PASSWORD is required when using PostgreSQL datasource";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment,
                                      SpringApplication application) {
    String url = environment.getProperty(DS_URL_KEY);
    if (url == null || !url.startsWith(PG_URL_PREFIX)) {
      return;
    }
    String password = environment.getProperty(PW_KEY);
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(ERROR_MESSAGE);
    }
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }
}
