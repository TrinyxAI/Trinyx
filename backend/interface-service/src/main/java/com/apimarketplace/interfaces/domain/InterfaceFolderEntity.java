package com.apimarketplace.interfaces.domain;

import com.apimarketplace.common.folder.AbstractResourceFolderEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A folder on the /app/interface list ({@code interface.interface_folders}, V450).
 * Everything about it comes from {@link AbstractResourceFolderEntity}, shared with the
 * folder tables of the other list pages; membership is the {@code folder_id} column on
 * {@link InterfaceEntity}.
 */
@Entity
@Table(name = "interface_folders")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class InterfaceFolderEntity extends AbstractResourceFolderEntity {
}
