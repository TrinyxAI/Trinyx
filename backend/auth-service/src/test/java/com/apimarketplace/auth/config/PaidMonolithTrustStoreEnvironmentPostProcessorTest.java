package com.apimarketplace.auth.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PaidMonolithTrustStoreEnvironmentPostProcessorTest {

    private static final String TRUSTSTORE_PROPERTY = "javax.net.ssl.trustStore";
    private static final String PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";
    private static final String TYPE_PROPERTY = "javax.net.ssl.trustStoreType";
    private static final String TEST_PASSWORD = "DO_NOT_EMIT_TEST_SENTINEL";

    private final Map<String, String> originalSystemProperties = new HashMap<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void saveAndClearSystemProperties() {
        for (String property : new String[]{
                TRUSTSTORE_PROPERTY, PASSWORD_PROPERTY, TYPE_PROPERTY}) {
            originalSystemProperties.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }

    @AfterEach
    void restoreSystemProperties() {
        originalSystemProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    @Test
    void loadsPasswordFromFileAndInitializesJdkTlsProperties(CapturedOutput output)
            throws IOException {
        Path trustStore = writeFile("truststore.p12", "fixture");
        Path passwordFile = writeFile("truststore.password", TEST_PASSWORD + System.lineSeparator());

        process(environment(trustStore, passwordFile));

        assertThat(System.getProperty(TRUSTSTORE_PROPERTY))
                .isEqualTo(trustStore.toString());
        assertThat(System.getProperty(PASSWORD_PROPERTY)).isEqualTo(TEST_PASSWORD);
        assertThat(System.getProperty(TYPE_PROPERTY)).isEqualTo("PKCS12");
        assertThat(output.getAll()).doesNotContain(TEST_PASSWORD);
    }

    @Test
    void runsBeforeSpringBeansCanInitializeTlsClients(CapturedOutput output)
            throws IOException {
        Path trustStore = writeFile("truststore.p12", "fixture");
        Path passwordFile = writeFile("truststore.password", TEST_PASSWORD);

        SpringApplication application =
                new SpringApplication(BootstrapProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                PaidMonolithTrustStoreEnvironmentPostProcessor.TRUSTSTORE_FILE_ENV,
                trustStore.toString(),
                PaidMonolithTrustStoreEnvironmentPostProcessor.TRUSTSTORE_PASSWORD_FILE_ENV,
                passwordFile.toString(),
                "spring.main.banner-mode", "off"));

        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getBean(TlsPropertyProbe.class).password())
                    .isEqualTo(TEST_PASSWORD);
        }

        assertThat(output.getAll()).doesNotContain(TEST_PASSWORD);
    }

    @Test
    void failsClosedWhenPasswordFileIsMissing() throws IOException {
        Path trustStore = writeFile("truststore.p12", "fixture");
        Path missingPasswordFile = tempDir.resolve("missing.password");

        assertThatThrownBy(() -> process(environment(trustStore, missingPasswordFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paid-monolith truststore password file is missing or unreadable")
                .hasMessageNotContaining(TEST_PASSWORD);
    }

    @Test
    void failsClosedWhenPasswordFileIsUnreadable() throws IOException {
        Path trustStore = writeFile("truststore.p12", "fixture");
        Path passwordFile = writeFile("truststore.password", TEST_PASSWORD);
        Assumptions.assumeTrue(
                passwordFile.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("---------"));
        Assumptions.assumeFalse(Files.isReadable(passwordFile));

        assertThatThrownBy(() -> process(environment(trustStore, passwordFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paid-monolith truststore password file is missing or unreadable")
                .hasMessageNotContaining(TEST_PASSWORD);
    }

    @Test
    void failsClosedWhenPasswordFileIsEmpty() throws IOException {
        Path trustStore = writeFile("truststore.p12", "fixture");
        Path passwordFile = writeFile("truststore.password", System.lineSeparator());

        assertThatThrownBy(() -> process(environment(trustStore, passwordFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paid-monolith truststore password file is empty")
                .hasMessageNotContaining(TEST_PASSWORD);
    }

    @Test
    void failsClosedWhenTrustStorePathIsMissing() throws IOException {
        Path passwordFile = writeFile("truststore.password", TEST_PASSWORD);
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        PaidMonolithTrustStoreEnvironmentPostProcessor.TRUSTSTORE_PASSWORD_FILE_ENV,
                        passwordFile.toString());

        assertThatThrownBy(() -> process(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paid-monolith truststore path is required when password-file configuration is enabled")
                .hasMessageNotContaining(TEST_PASSWORD);
    }

    @Test
    void failsClosedWhenTrustStoreFileIsMissing() throws IOException {
        Path missingTrustStore = tempDir.resolve("missing-truststore.p12");
        Path passwordFile = writeFile("truststore.password", TEST_PASSWORD);

        assertThatThrownBy(() -> process(environment(missingTrustStore, passwordFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paid-monolith truststore file is missing or unreadable")
                .hasMessageNotContaining(TEST_PASSWORD);
    }

    private MockEnvironment environment(Path trustStore, Path passwordFile) {
        return new MockEnvironment()
                .withProperty(
                        PaidMonolithTrustStoreEnvironmentPostProcessor.TRUSTSTORE_FILE_ENV,
                        trustStore.toString())
                .withProperty(
                        PaidMonolithTrustStoreEnvironmentPostProcessor.TRUSTSTORE_PASSWORD_FILE_ENV,
                        passwordFile.toString());
    }

    private void process(MockEnvironment environment) {
        new PaidMonolithTrustStoreEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(Object.class));
    }

    private Path writeFile(String name, String contents) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, contents, StandardCharsets.UTF_8);
        return path;
    }

    @Configuration(proxyBeanMethods = false)
    static class BootstrapProbeConfiguration {

        @Bean
        TlsPropertyProbe tlsPropertyProbe() {
            return new TlsPropertyProbe(System.getProperty(PASSWORD_PROPERTY));
        }
    }

    record TlsPropertyProbe(String password) {
    }
}
