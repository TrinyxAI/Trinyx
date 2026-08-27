package com.apimarketplace.datasource.persistence;

import com.apimarketplace.common.folder.AbstractResourceFolderEntity;
import com.apimarketplace.common.folder.FolderScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Folders of the /app/tables list, and which table is filed in which
 * ({@code datasource.datasource_folders} + {@code datasource_folder_items}, V451).
 *
 * <p>Plain JDBC, like the rest of this service's persistence - the shared folder logic
 * talks to a {@code ResourceFolderStore}, not to an ORM, precisely so a service can back it
 * whichever way it already works. The rows are mapped onto the same
 * {@link AbstractResourceFolderEntity} the JPA-backed services use, so the folder rules see
 * one shape everywhere.
 */
@Repository
public class DataSourceFolderRepository {

    /** The concrete folder row for this list. JPA annotations on the parent are inert here. */
    public static class DataSourceFolder extends AbstractResourceFolderEntity {
    }

    private static final RowMapper<DataSourceFolder> FOLDER_MAPPER = (rs, rowNum) -> mapFolder(rs);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public DataSourceFolderRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    // ===================== Folders =====================

    /** Every folder of the caller's workspace (org rows by organization, personal by owner). */
    public List<DataSourceFolder> findAllInScope(FolderScope scope) {
        if (scope.hasOrganization()) {
            return jdbc.query(
                    "SELECT * FROM datasource.datasource_folders WHERE organization_id = ?",
                    FOLDER_MAPPER, scope.organizationId());
        }
        return jdbc.query(
                "SELECT * FROM datasource.datasource_folders "
                        + "WHERE owner_id = ? AND organization_id IS NULL",
                FOLDER_MAPPER, scope.userId());
    }

    /** One folder by id, unscoped - the caller applies the workspace check. */
    public Optional<DataSourceFolder> findById(UUID id) {
        return jdbc.query("SELECT * FROM datasource.datasource_folders WHERE id = ?", FOLDER_MAPPER, id)
                .stream().findFirst();
    }

    /** Insert or update, mirroring a JPA {@code save}. */
    public DataSourceFolder save(DataSourceFolder folder) {
        Instant now = Instant.now();
        if (folder.getId() == null) {
            folder.setId(UUID.randomUUID());
            folder.setCreatedAt(now);
            folder.setUpdatedAt(now);
            jdbc.update(
                    "INSERT INTO datasource.datasource_folders "
                            + "(id, name, parent_folder_id, owner_id, organization_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    folder.getId(), folder.getName(), folder.getParentFolderId(),
                    folder.getOwnerId(), folder.getOrganizationId(),
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            return folder;
        }
        folder.setUpdatedAt(now);
        jdbc.update(
                "UPDATE datasource.datasource_folders "
                        + "SET name = ?, parent_folder_id = ?, updated_at = ? WHERE id = ?",
                folder.getName(), folder.getParentFolderId(), java.sql.Timestamp.from(now), folder.getId());
        return folder;
    }

    public void deleteAll(Collection<UUID> folderIds) {
        if (folderIds.isEmpty()) return;
        namedJdbc.update("DELETE FROM datasource.datasource_folders WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", folderIds));
    }

    // ===================== Membership =====================

    /**
     * Which folder each filed table of the workspace is in ({@code dataSourceId -> folderId}).
     * One small query per list request; a table filed nowhere is simply absent.
     */
    public Map<Long, UUID> findMembershipsInScope(FolderScope scope) {
        String sql = scope.hasOrganization()
                ? "SELECT data_source_id, folder_id FROM datasource.datasource_folder_items "
                        + "WHERE organization_id = ?"
                : "SELECT data_source_id, folder_id FROM datasource.datasource_folder_items "
                        + "WHERE owner_id = ? AND organization_id IS NULL";
        Object arg = scope.hasOrganization() ? scope.organizationId() : scope.userId();
        Map<Long, UUID> memberships = new HashMap<>();
        jdbc.query(sql, rs -> {
            memberships.put(rs.getLong("data_source_id"), (UUID) rs.getObject("folder_id"));
        }, arg);
        return memberships;
    }

    /**
     * File the given tables into {@code folderId}, or remove their filing when it is
     * {@code null}. Scoped to one workspace, so ids from another are not touched.
     *
     * @return how many tables were actually re-filed
     */
    public int assign(FolderScope scope, UUID folderId, Collection<Long> dataSourceIds, Collection<Long> allowedIds) {
        // Only ids the caller can actually see (resolved by the service from its own scoped
        // list) are ever written - the membership table carries no tenant column of the
        // resource, so this filter IS the authorization.
        List<Long> ids = dataSourceIds.stream().filter(allowedIds::contains).toList();
        if (ids.isEmpty()) return 0;

        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        if (folderId == null) {
            return namedJdbc.update(
                    "DELETE FROM datasource.datasource_folder_items WHERE data_source_id IN (:ids)", params);
        }
        int written = 0;
        for (Long id : ids) {
            written += jdbc.update(
                    "INSERT INTO datasource.datasource_folder_items "
                            + "(data_source_id, folder_id, organization_id, owner_id) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (data_source_id) DO UPDATE SET folder_id = EXCLUDED.folder_id, "
                            + "organization_id = EXCLUDED.organization_id, owner_id = EXCLUDED.owner_id",
                    id, folderId, scope.organizationId(), scope.userId());
        }
        return written;
    }

    /** Empty the given folders: the tables they held go back to the top level. */
    public void clearFolders(Collection<UUID> folderIds) {
        if (folderIds.isEmpty()) return;
        namedJdbc.update("DELETE FROM datasource.datasource_folder_items WHERE folder_id IN (:ids)",
                new MapSqlParameterSource("ids", folderIds));
    }

    private static DataSourceFolder mapFolder(ResultSet rs) throws SQLException {
        DataSourceFolder folder = new DataSourceFolder();
        folder.setId((UUID) rs.getObject("id"));
        folder.setName(rs.getString("name"));
        folder.setParentFolderId((UUID) rs.getObject("parent_folder_id"));
        folder.setOwnerId(rs.getString("owner_id"));
        folder.setOrganizationId(rs.getString("organization_id"));
        folder.setCreatedAt(rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant() : null);
        folder.setUpdatedAt(rs.getTimestamp("updated_at") != null
                ? rs.getTimestamp("updated_at").toInstant() : null);
        return folder;
    }
}
