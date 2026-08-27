package com.apimarketplace.credential.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A credential's IDENTITY: enough to decide which one a caller meant, and
 * nothing else.
 *
 * <p>This exists because the only other way to list an account's credentials
 * internally is {@code /api/internal/credentials/all}, which answers with whole
 * {@code Credential} records, decrypted secrets included. Pulling those in order
 * to pick an id would be the shape the credential code explicitly refuses
 * elsewhere: "asking for a secret in order to decide not to use it is the one
 * shape this check must not have"
 * ({@code HttpExecutionService.resolvePinnedCredentialOwnership}).
 *
 * <p>So: id, name, integration, status. No {@code credentialData}, ever. If a
 * future caller needs the secret it must ask for the credential by id, through
 * the paths that already exist for that.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialIdentityDto {

    private Long id;
    private String name;
    private String integration;
    private String status;

    public CredentialIdentityDto() {
    }

    public CredentialIdentityDto(Long id, String name, String integration, String status) {
        this.id = id;
        this.name = name;
        this.integration = integration;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIntegration() {
        return integration;
    }

    public void setIntegration(String integration) {
        this.integration = integration;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
