package com.apimarketplace.agent.domain;

import com.apimarketplace.common.folder.AbstractResourceFolderEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A folder on the /app/agent list ({@code agent.agent_folders}, V449). Everything about it
 * comes from {@link AbstractResourceFolderEntity}, shared with the folder tables of the
 * other list pages; membership is the {@code folder_id} column on {@link AgentEntity}.
 */
@Entity
@Table(name = "agent_folders")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentFolderEntity extends AbstractResourceFolderEntity {
}
