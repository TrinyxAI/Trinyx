package com.apimarketplace.common.folder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence the folder logic needs, and nothing more. Each service implements it
 * over its own Spring Data repository; {@link ResourceFolderCoreService} then holds the
 * rules (scope, cycles, cascade) once for all five list pages.
 *
 * <p>This indirection is why the shared logic can live in common-lib, which has the JPA
 * ANNOTATIONS but not Spring Data on its classpath - and it makes the rules unit-testable
 * against an in-memory store instead of a database.
 *
 * @param <E> the service's concrete folder entity
 */
public interface ResourceFolderStore<E extends AbstractResourceFolderEntity> {

    /** Every folder of this resource type visible in the given workspace. */
    List<E> findAllInScope(FolderScope scope);

    /** One folder by id, WITHOUT any scope filtering (the caller applies the scope). */
    Optional<E> findById(UUID id);

    /** A new, unsaved entity of the concrete type. */
    E newFolder();

    E save(E folder);

    void deleteAll(Collection<E> folders);

    /**
     * Move every resource currently filed in {@code folderIds} back to the top level.
     * Called when those folders are being deleted: a folder never deletes a user's
     * resource, it only stops holding it.
     */
    void detachResources(Collection<UUID> folderIds, FolderScope scope);
}
