package com.apimarketplace.publication.domain;

import com.apimarketplace.common.folder.AbstractResourceFolderEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A folder on the /app/applications list ({@code publication.application_folders}, V452).
 * Everything about it comes from {@link AbstractResourceFolderEntity}, shared with the
 * folder tables of the other list pages.
 *
 * <p>Unlike the other four, the MEMBERSHIP is not a column on the resource: an application
 * is a publication, and a publication row is shared between its publisher and everyone who
 * acquired it (and may not even live on this install). See {@link ApplicationFolderItemEntity}.
 */
@Entity
@Table(name = "application_folders")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ApplicationFolderEntity extends AbstractResourceFolderEntity {
}
