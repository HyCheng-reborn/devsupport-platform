package interview.guide.common.config;

import interview.guide.App;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("PostgresPasswordValidator")
class PostgresPasswordValidatorTest {

  private final PostgresPasswordValidator validator = new PostgresPasswordValidator();

  /**
   * Creates an isolated environment without system env vars or system properties
   * to prevent host machine contamination.
   */
  private static ConfigurableEnvironment isolatedEnv() {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().remove("systemEnvironment");
    env.getPropertySources().remove("systemProperties");
    return env;
  }

  @Nested
  @DisplayName("PostgreSQL URL — password validation")
  class PostgresUrl {

    @Test
    @DisplayName("U1: missing POSTGRES_PASSWORD → throws IllegalStateException")
    void missingPassword_throws() {
      ConfigurableEnvironment env = isolatedEnv();
      env.getPropertySources().addLast(
        new MapPropertySource("test",
          Map.of("spring.datasource.url", "jdbc:postgresql://localhost/db")));

      assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("POSTGRES_PASSWORD is required when using PostgreSQL datasource");
    }

    @Test
    @DisplayName("U2: empty string password → throws")
    void emptyPassword_throws() {
      ConfigurableEnvironment env = isolatedEnv();
      env.getPropertySources().addLast(
        new MapPropertySource("test",
          Map.of(
            "spring.datasource.url", "jdbc:postgresql://localhost/db",
            "POSTGRES_PASSWORD", "")));

      assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
        .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("U3: whitespace-only password → throws")
    void blankPassword_throws() {
      ConfigurableEnvironment env = isolatedEnv();
      env.getPropertySources().addLast(
        new MapPropertySource("test",
          Map.of(
            "spring.datasource.url", "jdbc:postgresql://localhost/db",
            "POSTGRES_PASSWORD", "   ")));

      assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
        .isInstanceOf(IllegalStateException.class);

      // Also verify Unicode whitespace (em space) is rejected
      env.getPropertySources().replace("test",
        new MapPropertySource("test",
          Map.of(
            "spring.datasource.url", "jdbc:postgresql://localhost/db",
            "POSTGRES_PASSWORD", "\u2003")));

      assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
        .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("U4: non-empty dummy password → passes")
    void dummyPassword_passes() {
      ConfigurableEnvironment env = isolatedEnv();
      env.getPropertySources().addLast(
        new MapPropertySource("test",
          Map.of(
            "spring.datasource.url", "jdbc:postgresql://localhost/db",
            "POSTGRES_PASSWORD", "dummy")));

      validator.postProcessEnvironment(env, null);
      // no exception
    }
  }

  @Nested
  @DisplayName("Non-PostgreSQL URL — skip validation")
  class NonPostgresUrl {

    @Test
    @DisplayName("U5: H2 URL without password → passes")
    void h2Url_noPassword_passes() {
      ConfigurableEnvironment env = isolatedEnv();
      env.getPropertySources().addLast(
        new MapPropertySource("test",
          Map.of(
            "spring.datasource.url", "jdbc:h2:mem:testdb")));

      validator.postProcessEnvironment(env, null);
      // no exception
    }

    @Test
    @DisplayName("U6: no datasource URL → passes")
    void noDatasourceUrl_passes() {
      ConfigurableEnvironment env = isolatedEnv();

      validator.postProcessEnvironment(env, null);
      // no exception
    }

    @Test
    @DisplayName("U7: empty datasource URL → passes (skip)")
    void emptyDatasourceUrl_passes() {
      ConfigurableEnvironment env = isolatedEnv();
      env.getPropertySources().addLast(
        new MapPropertySource("test",
          Map.of("spring.datasource.url", "")));

      validator.postProcessEnvironment(env, null);
      // no exception — empty URL does not match PG prefix
    }
  }

  @Test
  @DisplayName("U8: order is after ConfigDataEnvironmentPostProcessor")
  void orderIsAfterConfigData() {
    assertThat(validator.getOrder())
      .isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
  }

  // ── Integration timing test (I1) ──────────────────────────────────

  @Test
  @DisplayName("I1: Spring Boot bootstrap with PG URL + empty password fails in prepareEnvironment")
  void integrationBoot_emptyPassword_failsInPrepareEnvironment() {
    try {
      new SpringApplicationBuilder(App.class)
        .web(WebApplicationType.NONE)
        .run(
          "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/fake",
          "--POSTGRES_PASSWORD="
        );
    } catch (Exception ex) {
      // Verify exception type and message
      assertThat(ex).isInstanceOf(IllegalStateException.class);
      assertThat(ex.getMessage()).contains("POSTGRES_PASSWORD");

      // Collect full stack trace as string
      StringWriter sw = new StringWriter();
      ex.printStackTrace(new PrintWriter(sw));
      String stackTrace = sw.toString();

      // Timing evidence: validator fires during prepareEnvironment
      assertThat(stackTrace)
        .as("Stack should contain PostgresPasswordValidator.postProcessEnvironment")
        .contains("PostgresPasswordValidator.postProcessEnvironment");
      assertThat(stackTrace)
        .as("Stack should contain prepareEnvironment (before refresh)")
        .contains("prepareEnvironment");

      // Negative timing evidence: must NOT contain post-refresh phases
      assertThat(stackTrace)
        .as("Stack should NOT contain refreshContext (proves pre-refresh timing)")
        .doesNotContain("refreshContext");
      assertThat(stackTrace)
        .as("Stack should NOT contain finishBeanFactoryInitialization (proves pre-bean-init)")
        .doesNotContain("finishBeanFactoryInitialization");

      return;
    }
    fail("Expected IllegalStateException was not thrown");
  }
}
