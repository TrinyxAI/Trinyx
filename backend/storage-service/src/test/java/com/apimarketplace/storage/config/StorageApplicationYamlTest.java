package com.apimarketplace.storage.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class StorageApplicationYamlTest {

    @Test
    void applicationYamlParsesAndKeepsShowcasePathPublic() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties).containsValue("/api/files/proxy-signed");
        assertThat(properties.stringPropertyNames())
                .anyMatch(name -> name.startsWith("gateway.filter.public-paths["));
        assertThat(properties.stringPropertyNames())
                .noneMatch(name -> name.matches(
                        "gateway[.]filter[.]service-route-permissions[.]\\d+.*"));
    }
}
