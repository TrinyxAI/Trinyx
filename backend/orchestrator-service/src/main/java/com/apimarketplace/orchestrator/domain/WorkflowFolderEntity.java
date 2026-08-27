package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.folder.AbstractResourceFolderEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A folder on the /app/workflow list ({@code orchestrator.workflow_folders}, V448).
 * Everything about it - columns, scope, nesting - comes from
 * {@link AbstractResourceFolderEntity}, shared with the folder tables of the other list
 * pages; membership is the {@code folder_id} column on {@link WorkflowEntity}.
 */
@Entity
@Table(name = "workflow_folders")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WorkflowFolderEntity extends AbstractResourceFolderEntity {
}
