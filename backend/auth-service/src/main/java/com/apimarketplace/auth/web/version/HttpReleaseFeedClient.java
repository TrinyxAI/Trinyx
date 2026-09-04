package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * RestClient-backed {@link ReleaseFeedClient}: GET of the configured cloud release feed with a
 * short timeout. Created only in the embedded (CE) edition, the only one that polls for updates.
 *
 * <p>Carries this install's anonymous id ({@link CeInstallHeaders#INSTALL_ID}) when
 * {@link CeInstallIdProvider} is present, which is what lets the cloud count live self-hosted
 * installs at all. The provider is absent when the operator opted out
 * ({@code ce.version-check.send-install-id=false}), and then the request is anonymous exactly as it
 * was before - the update check itself is unchanged either way.
 */
@Component
@ConditionalOnExpression("'${auth.mode:keycloak}' == 'embedded'")
public class HttpReleaseFeedClient implements ReleaseFeedClient {

    private final RestClient restClient;
    private final String feedUrl;
    private final ObjectProvider<CeInstallIdProvider> installId;

    public HttpReleaseFeedClient(
            @Value("${ce.version-check.url:https://app.trinyx.fr/api/ce/releases/latest}") String feedUrl,
            @Value("${ce.version-check.timeout-ms:5000}") int timeoutMs,
            ObjectProvider<CeInstallIdProvider> installId) {
        this.feedUrl = feedUrl;
        this.installId = installId;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public LatestRelease fetchLatest(String currentVersion) {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri(feedUrl, uri -> uri.queryParam("current", currentVersion).build());
        UUID id = resolveInstallId();
        if (id != null) {
            request = request.header(CeInstallHeaders.INSTALL_ID, id.toString());
        }
        return request.retrieve().body(LatestRelease.class);
    }

    /**
     * @return the anonymous install id, or {@code null} when opted out or unreadable
     */
    private UUID resolveInstallId() {
        CeInstallIdProvider provider = installId.getIfAvailable();
        return provider == null ? null : provider.current().orElse(null);
    }
}
