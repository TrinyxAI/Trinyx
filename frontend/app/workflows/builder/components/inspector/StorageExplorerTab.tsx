'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import {
  LayoutGrid,
  List,
  Search,
  ChevronLeft,
  ChevronRight,
  RefreshCw,
  Database,
  Trash2,
  X,
  Download,
} from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useStorageExplorer } from './useStorageExplorer';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { storageApi, S3_FILES_FILTER } from '@/lib/api/storage-api';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { type FileTypeCategory, matchesFileType } from '@/lib/files/fileTypes';
import { folderNavKey, folderLabel } from '@/lib/files/virtualFolders';
import { backTarget } from '@/lib/files/folderBreadcrumb';
import type { FilesFolderCrumb } from '@/lib/files/filesHeaderBus';
import { useAuthToken } from '@/hooks/useAuthToken';
import { FileDetailView } from '@/components/app/FileDetailView';
import { FilesExplorerBody } from '@/components/files/FilesExplorerBody';
import LoadingSpinner from '@/components/LoadingSpinner';
import { usePersistentState } from '@/hooks/usePersistentState';
import { FILES_PICKER_VIEW_MODE_STORAGE_KEY, normalizeViewMode, type FilesViewMode } from '@/lib/files/filesViewPreferences';


interface StorageExplorerTabProps {
  workflowId?: string;
  /** When provided, each row shows a select button and calls this on click (picker mode). */
  onSelect?: (entry: StorageExplorerEntry) => void;
  /**
   * When provided, the tab finds the entry whose {@code s3Key} matches (or ends with)
   * this value, highlights it and scrolls it into view. Used by chat-card click-throughs
   * (e.g. the inline image-generation card → Files tab focused on the clicked image).
   */
  focusS3Key?: string;
  /**
   * Like {@link focusS3Key} but matches by the opaque storage-row id - the highlight that
   * works for a file with no s3 key (e.g. a project file). Set by {@code openFilesPanel}'s
   * back chevron so returning from the detail re-highlights the file you were viewing.
   */
  focusEntryId?: string;
  /**
   * Force the FLAT (no folders) listing. Folder navigation is otherwise on for BOTH modes;
   * this is the deep-link caller's opt-out. The folder-aware grouping otherwise lists
   * the CURRENT level only, so a deep-link to a file nested under a (virtual workflow) folder
   * couldn't surface it at root - the image-generation card opts in to flat so its generated
   * image is findable wherever it lives. Default {@code false}: both modes show folders.
   */
  flat?: boolean;
  /**
   * Restored when the user navigates back from {@link FileDetailView}. The detail
   * view's back chevron re-mounts this tab; without these props the mount would
   * reset the user to page 0 / empty search / all-filetypes, losing their place.
   */
  initialPage?: number;
  initialSearch?: string;
  initialFileType?: FileTypeCategory;
  /**
   * Picker mode only: which density to open in when the user has expressed no preference.
   * Defaults to the dense list, which is safe in a narrow container; the media-cell dialog opts
   * into tiles because seeing WHAT a file looks like is most of that decision.
   */
  defaultView?: FilesViewMode;
  /**
   * Which picker this is, so its density preference is remembered separately. The two callers
   * have opposite geometry and must not share one stored choice.
   */
  viewScope?: 'dialog' | 'field';
}

/**
 * Storage Explorer tab component.
 * Lists ONLY real object-storage files (S3) - the same set as the Files page
 * (app/file). Both the explorer side-panel and the inline picker share this set,
 * filtered server-side by the {@link S3_FILES_FILTER} single source of truth.
 */
export function StorageExplorerTab({ workflowId, onSelect, focusS3Key, focusEntryId, flat, initialPage, initialSearch, initialFileType, defaultView = 'list', viewScope = 'field' }: StorageExplorerTabProps) {
  const t = useTranslations('storageExplorer');
  // Virtual/manual folder labels live in the `files` namespace (Epoch/Run/Item +
  // the "Files" root crumb), reused as-is from the full-page browser.
  const tFiles = useTranslations('files');
  // Explorer mode (side-panel Files tab) paginates 50 per page; picker mode (form
  // fields) uses the smaller 20-item page so it doesn't drown shorter forms. BOTH
  // apply S3_FILES_FILTER (s3Only) so they list exactly the Files page's set - every
  // S3-backed file, including workflow outputs - with no source-type dropdown.
  const isExplorerMode = !onSelect;
  const explorerPageSize = isExplorerMode ? 50 : 20;
  // Folder-tree navigation, unless the caller forces FLAT (the image-gen deep-link, whose
  // target may live under a virtual workflow folder). A focus key/id no longer flattens:
  // folders + files share one per-day timeline, so a focused file at the current level is
  // still visible AND highlighted.
  //
  // The PICKER used to be flat too, which meant it listed every file in the org as one
  // undifferentiated stream: no folders, no way to narrow by where a file lives, and files
  // nested inside folders mixed in with the rest. Folder-aware is how you find something.
  const enableFolders = !flat;

  // In the PICKER, browsing is folder-scoped but SEARCHING is not. The folder-aware root lists
  // only files with no parent folder AND no workflow, so making the picker folder-aware shrank
  // its search from "every file in the workspace" to "loose root files", losing precisely the
  // workflow output a media cell most often wants. A picker is a find-one-thing surface, so a
  // search there drops back to the flat listing - which is both its previous reach and what a
  // search box is taken to mean.
  //
  // The EXPLORER deliberately does NOT do this. It is a browser: a search there filters the
  // folder you are standing in, and flattening would discard that context mid-keystroke. It also
  // has to keep agreeing with the full-page Files browser, which is hard-coded folder-aware and
  // shares this very body - two surfaces with one body must not search by opposite rules.
  const [searchActive, setSearchActive] = React.useState<boolean>(!!initialSearch?.trim());
  const folderAware = enableFolders && !(searchActive && !isExplorerMode);

  // Grid (thumbnails) or list (dense rows), offered in the picker only - the side-panel explorer
  // keeps its narrow rows. Both the DEFAULT and the stored preference belong to the CALLER, not
  // to "the picker", because the two callers have opposite geometry: the media-cell dialog is a
  // wide modal where tiles are the point, the inspector's file field is a ~250px column where a
  // five-across tile grid is unreadable. One shared key meant a single click in the modal
  // re-imposed that grid on the narrow field, for a user who never opened the modal.
  const [pickerViewMode, setPickerViewMode] = usePersistentState<FilesViewMode>(
    `${FILES_PICKER_VIEW_MODE_STORAGE_KEY}.${viewScope}`,
    defaultView,
    { deserializer: normalizeViewMode },
  );
  const bodyVariant: 'grid' | 'compact' =
    !isExplorerMode && pickerViewMode === 'grid' ? 'grid' : 'compact';
  const {
    entries,
    totalElements,
    totalPages,
    currentPage,
    pageSize,
    loading,
    error,
    search,
    setSearch,
    setPage,
    refresh,
    parentFolderId,
    navigateToFolder,
  } = useStorageExplorer(workflowId, undefined, undefined, {
    pageSize: explorerPageSize,
    initialPage,
    folderAware,
    virtualWorkflowFolders: folderAware,
    // BOTH the explorer side-panel AND the picker list S3-BACKED files (s3Only), exactly like
    // /app/files - NOT sourceType='S3_FILE'. The latter excluded workflow OUTPUT files
    // (STEP_OUTPUT / INTERFACE_SCREENSHOT): they are S3-backed but not 'S3_FILE'-sourced, so
    // their virtual workflow folders were missing from the side panel while /app/files showed them.
    ...S3_FILES_FILTER,
  });

  const [searchInput, setSearchInput] = React.useState(initialSearch ?? search);
  const searchTimeoutRef = React.useRef<ReturnType<typeof setTimeout>>(undefined);
  // No filetype-default on focus - user shouldn't be locked to "images" just
  // because they came from an image-gen card.
  const [fileTypeFilter, setFileTypeFilter] = React.useState<FileTypeCategory>(initialFileType ?? '_all');
  // Map (not Set) so the selection survives pagination - when the user moves
  // from page 1 to page 2, the page-1 entries leave `filteredEntries`, but we
  // still need their s3Key / fileName / storageType for bulk download. Bulk
  // delete only needs ids; we use the map keys for that.
  const [selectedEntries, setSelectedEntries] = React.useState<Map<string, StorageExplorerEntry>>(new Map());
  const selectedIds = React.useMemo(() => new Set(selectedEntries.keys()), [selectedEntries]);
  const [deleting, setDeleting] = React.useState(false);
  const [downloading, setDownloading] = React.useState(false);
  const [expandedId, setExpandedId] = React.useState<string | null>(null);
  const [showDeleteModal, setShowDeleteModal] = React.useState(false);

  // ---- Folder-tree navigation (both modes; see enableFolders / folderAware) ----
  // The breadcrumb trail the user has navigated into (root → … → current). The
  // hook owns the real parentFolderId query param; this trail is the display +
  // navigation history. Mirrors FileBrowser, compacted for the narrow panel.
  const [folderTrail, setFolderTrail] = React.useState<FilesFolderCrumb[]>([]);

  const enterFolder = React.useCallback((entry: StorageExplorerEntry) => {
    const navKey = folderNavKey(entry);
    if (!navKey) return; // malformed row (no id and no virtualId) - no-op.
    setFolderTrail((prev) => [...prev, { id: navKey, name: folderLabel(entry, tFiles) }]);
    navigateToFolder(navKey);
  }, [navigateToFolder, tFiles]);

  // Navigate to a specific folder id in the trail (or null = root), truncating the
  // trail at that folder. Drives the breadcrumb crumbs + the back-up-one button.
  const goToFolder = React.useCallback((folderId: string | null) => {
    setFolderTrail((prev) => {
      if (folderId === null) return [];
      const idx = prev.findIndex((c) => c.id === folderId);
      return idx >= 0 ? prev.slice(0, idx + 1) : prev;
    });
    navigateToFolder(folderId);
  }, [navigateToFolder]);

  // Seed initial search into the hook on first mount when restored from a back-nav.
  // We do this once via a ref so subsequent searchInput edits stay in control of the
  // 300ms-debounced handler below.
  const initialSearchAppliedRef = React.useRef(false);
  React.useEffect(() => {
    if (initialSearchAppliedRef.current) return;
    initialSearchAppliedRef.current = true;
    if (initialSearch && initialSearch !== search) {
      setSearch(initialSearch);
      setSearchActive(!!initialSearch.trim());
    }
  }, [initialSearch, search, setSearch]);

  const handleSearchChange = React.useCallback(
    (value: string) => {
      setSearchInput(value);
      if (searchTimeoutRef.current) {
        clearTimeout(searchTimeoutRef.current);
      }
      searchTimeoutRef.current = setTimeout(() => {
        setSearch(value);
        // Flipped on the SAME debounce tick as the query, so browsing-vs-searching changes scope
        // exactly once per search rather than on every keystroke. Deliberately inert in explorer
        // mode: folderAware short-circuits before reading it there.
        setSearchActive(!!value.trim());
      }, 300);
    },
    [setSearch]
  );

  React.useEffect(() => {
    return () => {
      if (searchTimeoutRef.current) {
        clearTimeout(searchTimeoutRef.current);
      }
    };
  }, []);

  // Folders are NEVER filtered by file type. A folder carries no mime type, so an "images"
  // filter matched none of them and silently emptied the folder list - which is exactly the
  // state a thumbnail column's picker opens in, since it seeds that filter. The result was a
  // breadcrumb with nothing to navigate into: the folder feature, inert on its main caller.
  const filteredEntries = React.useMemo(
    () => entries.filter((e) => e.isFolder || matchesFileType(e, fileTypeFilter)),
    [entries, fileTypeFilter]
  );

  // File rows only (excludes folders when the listing is folder-aware) - drives the SELECTION helpers
  // below: a folder isn't selectable, and a VIRTUAL folder has id === null which must
  // never enter the selection Map. The shared body owns folder/file rendering itself.
  const fileEntries = React.useMemo(
    () => (folderAware ? filteredEntries.filter((e) => !e.isFolder) : filteredEntries),
    [folderAware, filteredEntries]
  );

  // Selection helpers (only in explorer mode, not picker). Selection operates on
  // FILE rows only - a folder isn't selectable, and a VIRTUAL folder has id ===
  // null which must never enter the selection Map. In flat mode this is just
  // filteredEntries (no folders present).
  const selectableEntries = folderAware ? fileEntries : filteredEntries;

  // Lookup by id from the visible list - toggleSelection only has the id (from
  // the row), so we have to find the entry to cache it.
  const entryById = React.useMemo(() => {
    const m = new Map<string, StorageExplorerEntry>();
    for (const e of selectableEntries) m.set(e.id, e);
    return m;
  }, [selectableEntries]);

  const toggleSelection = React.useCallback((id: string) => {
    setSelectedEntries((prev) => {
      const next = new Map(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        const entry = entryById.get(id);
        if (entry) next.set(id, entry);
      }
      return next;
    });
  }, [entryById]);

  // "Select all" toggles only the current page. If every visible row is already
  // selected, we unselect just those (off-page selections survive); otherwise
  // we add all visible rows on top of any existing off-page picks.
  const allVisibleSelected = selectableEntries.length > 0 && selectableEntries.every((e) => selectedEntries.has(e.id));
  const toggleSelectAll = React.useCallback(() => {
    setSelectedEntries((prev) => {
      const next = new Map(prev);
      const allSelected = selectableEntries.length > 0 && selectableEntries.every((e) => next.has(e.id));
      if (allSelected) {
        for (const e of selectableEntries) next.delete(e.id);
      } else {
        for (const e of selectableEntries) next.set(e.id, e);
      }
      return next;
    });
  }, [selectableEntries]);

  const toggleDaySelection = React.useCallback((dayEntries: StorageExplorerEntry[]) => {
    setSelectedEntries((prev) => {
      const next = new Map(prev);
      const allSelected = dayEntries.every((e) => next.has(e.id));
      if (allSelected) {
        for (const e of dayEntries) next.delete(e.id);
      } else {
        for (const e of dayEntries) next.set(e.id, e);
      }
      return next;
    });
  }, []);

  const toggleExpanded = React.useCallback((id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  }, []);

  // Explorer-mode detail view is rendered INLINE (this state + the early return
  // near the JSX), NOT by swapping the side-panel tab content. That keeps THIS
  // component mounted while a file is open, so pagination (setPage) and the
  // cross-page effect below stay alive - the previous tab-swap approach
  // unmounted the explorer the instant the detail opened, which silently broke
  // page-edge navigation (setPage hit a dead fiber). Back is just clearing the
  // state, which preserves the user's page/search/filter for free. Picker-mode
  // (onSelect provided) never opens the detail.
  const [detailEntry, setDetailEntry] = React.useState<StorageExplorerEntry | null>(null);
  const navigateToDetail = React.useCallback((entry: StorageExplorerEntry) => {
    if (!entry.s3Key) return;
    setDetailEntry(entry);
  }, []);

  // Cross-page prev/next: when ‹/› hits a page edge we change the page, then -
  // once the target page is current and loaded - open its last ('prev') or
  // first ('next') file. The page-guard avoids opening a stale page's edge.
  const pendingDetailNavRef = React.useRef<{ dir: 'first' | 'last'; page: number; fromEntries: StorageExplorerEntry[] } | null>(null);
  React.useEffect(() => {
    const pending = pendingDetailNavRef.current;
    if (!pending) return;
    // A fetch error means the target page will never arrive - abandon the pending
    // nav (the user stays on the current file) instead of leaving it stuck.
    if (error) { pendingDetailNavRef.current = null; return; }
    // Open the new page's edge file only once the TARGET page is current AND its
    // data has actually been refetched - `entries` identity must differ from the
    // page we left. This skips the stale intermediate render where currentPage
    // already advanced but the old page's `entries` + loading=false still linger
    // (the fetch hasn't flipped loading=true yet), which would otherwise open the
    // wrong sibling (an edge file of the page we just left).
    if (loading || currentPage !== pending.page || entries === pending.fromEntries) return;
    if (filteredEntries.length === 0) {
      // Target page is entirely filtered out (client-side fileType filter over a
      // server-paginated page) - keep going the same direction to the next page
      // with a visible file, or stop if we run out of pages.
      if (pending.dir === 'first' && currentPage < totalPages - 1) {
        pendingDetailNavRef.current = { dir: 'first', page: currentPage + 1, fromEntries: entries };
        setPage(currentPage + 1);
      } else if (pending.dir === 'last' && currentPage > 0) {
        pendingDetailNavRef.current = { dir: 'last', page: currentPage - 1, fromEntries: entries };
        setPage(currentPage - 1);
      } else {
        pendingDetailNavRef.current = null;
      }
      return;
    }
    pendingDetailNavRef.current = null;
    setDetailEntry(pending.dir === 'first' ? filteredEntries[0] : filteredEntries[filteredEntries.length - 1]);
  }, [loading, error, currentPage, entries, filteredEntries, totalPages, setPage]);

  // Auto-focus the entry matching focusS3Key once entries load. We only do this
  // ONCE per focusS3Key value - the user may collapse afterwards and we must not
  // fight them. Match prefers exact equality, falls back to suffix to tolerate
  // slight key normalisation (the chat card sends img.path which is the same
  // s3Key the catalog dehydrator wrote, but defensive endsWith covers reruns).
  const focusedRowRef = React.useRef<HTMLDivElement | null>(null);
  const focusedKeyAppliedRef = React.useRef<string | null>(null);
  React.useEffect(() => {
    const token = focusEntryId ?? focusS3Key ?? null;
    if (!token || entries.length === 0) return;
    if (focusedKeyAppliedRef.current === token) return;
    // Match by opaque id first (works for a file with no s3 key), then by s3 key
    // (exact, then suffix either way to tolerate key normalisation).
    const match =
      (focusEntryId ? entries.find((e) => e.id === focusEntryId) : undefined)
      ?? (focusS3Key
            ? (entries.find((e) => e.s3Key === focusS3Key)
               ?? entries.find((e) => e.s3Key && focusS3Key.endsWith(e.s3Key))
               ?? entries.find((e) => e.s3Key && e.s3Key.endsWith(focusS3Key)))
            : undefined);
    if (!match) return;
    focusedKeyAppliedRef.current = token;
    // The shared body highlights the matching row and attaches focusedRowRef to it;
    // defer the scroll to the next paint so that ref is wired before we scroll.
    requestAnimationFrame(() => {
      focusedRowRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }, [focusS3Key, focusEntryId, entries]);

  const handleConfirmDelete = React.useCallback(async () => {
    if (selectedEntries.size === 0) return;
    setDeleting(true);
    try {
      await storageApi.deleteEntries(Array.from(selectedEntries.keys()));
      setSelectedEntries(new Map());
      setShowDeleteModal(false);
      refresh();
    } catch (err) {
      console.error('Failed to delete entries:', err);
    } finally {
      setDeleting(false);
    }
  }, [selectedEntries, refresh]);

  // Bulk download: stream the whole selection as a SINGLE .zip from the server
  // and save it with ONE programmatic download. The old approach looped and
  // triggered one <a download> per file, which the browser blocks after the
  // first (multiple-downloads gate) - so only one file ever landed. Reads from
  // `selectedEntries` (not filteredEntries) so selections across pages are kept.
  const token = useAuthToken();
  const handleBulkDownload = React.useCallback(async () => {
    if (selectedEntries.size === 0) return;
    setDownloading(true);
    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...getActiveOrgHeaderForRequest(),
      };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch('/api/proxy/storage/explorer/download-zip', {
        method: 'POST',
        headers,
        body: JSON.stringify({ ids: Array.from(selectedEntries.keys()) }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `files-${new Date().toISOString().slice(0, 10)}.zip`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Bulk download failed:', err);
    } finally {
      setDownloading(false);
    }
  }, [selectedEntries, token]);

  // Reset selection ONLY when filters change (search/source/filetype) OR when
  // entering/leaving a folder (selections only make sense within one listing).
  // Page changes deliberately preserve selection so the user can build a
  // multi-page selection for bulk delete/download.
  React.useEffect(() => {
    setSelectedEntries(new Map());
  }, [search, fileTypeFilter, workflowId, parentFolderId]);

  // Inline detail view (explorer mode) - replaces the list while open. Prev/next
  // walk the visible list and hop to the adjacent page at the edges (the cross-
  // page effect above opens the new page's edge file once it has loaded).
  if (detailEntry && detailEntry.s3Key) {
    const idx = filteredEntries.findIndex((e) => e.id === detailEntry.id);
    const hasPrev = idx > 0 || currentPage > 0;
    const hasNext = (idx >= 0 && idx < filteredEntries.length - 1) || currentPage < totalPages - 1;
    const goPrev = () => {
      if (idx > 0) setDetailEntry(filteredEntries[idx - 1]);
      else if (currentPage > 0) {
        pendingDetailNavRef.current = { dir: 'last', page: currentPage - 1, fromEntries: entries };
        setPage(currentPage - 1);
      }
    };
    const goNext = () => {
      if (idx >= 0 && idx < filteredEntries.length - 1) setDetailEntry(filteredEntries[idx + 1]);
      else if (currentPage < totalPages - 1) {
        pendingDetailNavRef.current = { dir: 'first', page: currentPage + 1, fromEntries: entries };
        setPage(currentPage + 1);
      }
    };
    return (
      <FileDetailView
        s3Key={detailEntry.s3Key}
        entryId={detailEntry.id}
        fileName={detailEntry.fileName ?? undefined}
        mimeType={detailEntry.mimeType ?? undefined}
        sizeBytes={detailEntry.sizeBytes ?? undefined}
        createdAt={detailEntry.createdAt}
        onPrev={hasPrev ? goPrev : undefined}
        onNext={hasNext ? goNext : undefined}
        onBack={() => setDetailEntry(null)}
      />
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Filters */}
      <div className="flex-shrink-0 p-3 space-y-2 border-b border-theme">
        {/* Search */}
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-[var(--text-secondary)]" />
          <Input
            value={searchInput}
            onChange={(e) => handleSearchChange(e.target.value)}
            placeholder={t('searchPlaceholder')}
            className="pl-8"
          />
        </div>

        {/* Search, density (picker only) and file-type filter */}
        {/* Grid / list, picker only. Same control and same i18n as the Files page so the two
            do not drift into two different vocabularies for one idea. */}
        {!isExplorerMode && (
          <div className="flex items-center rounded-lg border border-theme overflow-hidden flex-shrink-0">
            <button
              type="button"
              onClick={() => setPickerViewMode('grid')}
              aria-pressed={pickerViewMode === 'grid'}
              title={tFiles('viewGrid')}
              className={`h-8 px-2.5 flex items-center justify-center transition-colors ${pickerViewMode === 'grid' ? 'bg-[var(--bg-tertiary)] text-[var(--text-primary)]' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)]/50'}`}
            >
              <LayoutGrid className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={() => setPickerViewMode('list')}
              aria-pressed={pickerViewMode === 'list'}
              title={tFiles('viewList')}
              className={`h-8 px-2.5 flex items-center justify-center transition-colors ${pickerViewMode === 'list' ? 'bg-[var(--bg-tertiary)] text-[var(--text-primary)]' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)]/50'}`}
            >
              <List className="h-3.5 w-3.5" />
            </button>
          </div>
        )}
        <div className="flex gap-2">
          <Select value={fileTypeFilter} onValueChange={(v) => setFileTypeFilter(v as FileTypeCategory)}>
            <SelectTrigger className="flex-1">
              <SelectValue placeholder={t('allFileTypes')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="_all">{t('allFileTypes')}</SelectItem>
              <SelectItem value="images">{t('fileTypeImages')}</SelectItem>
              <SelectItem value="pdf">{t('fileTypePdf')}</SelectItem>
              <SelectItem value="documents">{t('fileTypeDocuments')}</SelectItem>
              <SelectItem value="spreadsheets">{t('fileTypeSpreadsheets')}</SelectItem>
              <SelectItem value="presentations">{t('fileTypePresentations')}</SelectItem>
              <SelectItem value="video">{t('fileTypeVideo')}</SelectItem>
              <SelectItem value="audio">{t('fileTypeAudio')}</SelectItem>
              <SelectItem value="archives">{t('fileTypeArchives')}</SelectItem>
              <SelectItem value="text">{t('fileTypeText')}</SelectItem>
              <SelectItem value="code">{t('fileTypeCode')}</SelectItem>
            </SelectContent>
          </Select>

          {/* No source-type dropdown: both modes list S3 files only (like the
              Files page), so a source selector would only offer empty non-S3
              buckets. The file-type filter above is the only narrowing control. */}

          <button
            type="button"
            onClick={refresh}
            disabled={loading}
            className="p-1.5 text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)] rounded-lg transition-colors"
            title={t('refresh')}
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      {/* Selection bar (explorer mode only). Gated on the SELECTABLE (file) rows so
          a folder-only listing in folder mode shows no inert select-all; in flat
          mode selectableEntries === filteredEntries so this is unchanged. */}
      {isExplorerMode && selectableEntries.length > 0 && (
        <div className="flex-shrink-0 flex items-center justify-between px-3 py-1.5 border-b border-theme bg-[var(--bg-secondary)]">
          <label className="flex items-center gap-2 cursor-pointer text-xs text-[var(--text-secondary)]">
            <input
              type="checkbox"
              checked={allVisibleSelected}
              ref={(el) => {
                if (!el) return;
                // Indeterminate when some-but-not-all of the selectable rows are
                // picked. Off-page selections don't enter this calculus -
                // toggleSelectAll only operates on what's on screen.
                const visibleSelectedCount = selectableEntries.reduce((n, e) => n + (selectedEntries.has(e.id) ? 1 : 0), 0);
                el.indeterminate = visibleSelectedCount > 0 && visibleSelectedCount < selectableEntries.length;
              }}
              onChange={toggleSelectAll}
              className="rounded border-slate-300 dark:border-slate-600"
            />
            {selectedEntries.size > 0
              ? t('selectedCount', { count: selectedEntries.size })
              : t('selectAll')}
          </label>
          {selectedIds.size > 0 && (
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={handleBulkDownload}
                disabled={downloading || deleting}
                className="flex items-center gap-1 px-2 py-1 rounded text-xs font-medium text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 disabled:opacity-50 transition-colors"
              >
                {downloading ? (
                  <LoadingSpinner size="xs" />
                ) : (
                  <Download className="h-3 w-3" />
                )}
                {t('downloadSelected')}
              </button>
              <button
                type="button"
                onClick={() => setShowDeleteModal(true)}
                disabled={deleting || downloading}
                className="flex items-center gap-1 px-2 py-1 rounded text-xs font-medium text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 disabled:opacity-50 transition-colors"
              >
                {deleting ? (
                  <LoadingSpinner size="xs" />
                ) : (
                  <Trash2 className="h-3 w-3" />
                )}
                {t('deleteSelected')}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Folder breadcrumb (folder mode only). One compact, horizontally-scroll
          row: a back-up-one chevron + the "Files" root crumb + each trail
          segment, all clickable. Rendered only once the hook is folder-aware
          (parentFolderId !== undefined). */}
      {folderAware && parentFolderId !== undefined && (
        <div className="flex-shrink-0 flex items-center gap-1 px-3 py-1.5 border-b border-theme bg-[var(--bg-secondary)] overflow-x-auto whitespace-nowrap text-xs">
          <button
            type="button"
            onClick={() => goToFolder(backTarget(folderTrail))}
            disabled={folderTrail.length === 0}
            className="p-0.5 rounded hover:bg-[var(--bg-tertiary)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors flex-shrink-0"
            aria-label="back"
          >
            <ChevronLeft className="h-3.5 w-3.5 text-[var(--text-secondary)]" />
          </button>
          <button
            type="button"
            onClick={() => goToFolder(null)}
            className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors flex-shrink-0"
          >
            {tFiles('title')}
          </button>
          {folderTrail.map((crumb) => (
            <React.Fragment key={crumb.id}>
              <ChevronRight className="h-3 w-3 text-[var(--text-muted)] flex-shrink-0" />
              <button
                type="button"
                onClick={() => goToFolder(crumb.id)}
                className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors truncate max-w-[8rem]"
                title={crumb.name}
              >
                {crumb.name}
              </button>
            </React.Fragment>
          ))}
        </div>
      )}

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {error && (
          <div className="p-3 text-sm text-red-500">
            {error}
          </div>
        )}

        {!loading && filteredEntries.length === 0 && !error && (
          <div className="flex flex-col items-center justify-center py-12 text-[var(--text-secondary)]">
            <Database className="h-8 w-8 mb-2" />
            <p className="text-sm">{t('noEntries')}</p>
          </div>
        )}

        {/* The shared FilesExplorerBody (compact density): folders first (sorted by last
            activity), then files grouped into collapsible per-day sections (newest first)
            - the SAME body the full-page Files browser + project Files tab render. The picker
            chooses its density (tiles or rows); the explorer is always rows. In picker mode
            rows select (and expand-preview, rows only); in explorer mode they navigate to the
            per-file detail. */}
        {filteredEntries.length > 0 && (
          <FilesExplorerBody
            variant={bodyVariant}
            entries={filteredEntries}
            enableFolders={folderAware}
            tFiles={tFiles}
            onOpenFolder={enterFolder}
            onOpenFile={isExplorerMode ? navigateToDetail : undefined}
            onSelectFile={onSelect}
            selectable={isExplorerMode}
            selectedIds={selectedIds}
            onToggleSelect={toggleSelection}
            onToggleDaySelection={isExplorerMode ? toggleDaySelection : undefined}
            focusS3Key={focusS3Key}
            focusEntryId={focusEntryId}
            focusRef={focusedRowRef}
            expandedId={expandedId}
            onToggleExpand={toggleExpanded}
          />
        )}

        {loading && filteredEntries.length === 0 && (
          <div className="flex items-center justify-center py-12">
            <RefreshCw className="h-5 w-5 animate-spin text-[var(--text-secondary)]" />
          </div>
        )}
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex-shrink-0 flex items-center justify-between px-3 py-2 border-t border-theme bg-[var(--bg-primary)]">
          <span className="text-xs text-[var(--text-secondary)]">
            {t('pagination', {
              from: currentPage * pageSize + 1,
              to: Math.min((currentPage + 1) * pageSize, totalElements),
              total: totalElements,
            })}
          </span>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setPage(currentPage - 1)}
              disabled={currentPage === 0}
              className="p-1 rounded hover:bg-[var(--bg-tertiary)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronLeft className="h-4 w-4 text-[var(--text-secondary)]" />
            </button>
            <span className="text-xs text-[var(--text-secondary)] min-w-[3rem] text-center">
              {currentPage + 1} / {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage(currentPage + 1)}
              disabled={currentPage >= totalPages - 1}
              className="p-1 rounded hover:bg-[var(--bg-tertiary)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronRight className="h-4 w-4 text-[var(--text-secondary)]" />
            </button>
          </div>
        </div>
      )}

      {/* Delete confirmation modal */}
      {showDeleteModal && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white dark:bg-gray-800 rounded-xl shadow-2xl max-w-sm w-full overflow-hidden relative">
            <Button
              onClick={() => setShowDeleteModal(false)}
              variant="ghost"
              size="icon"
              className="h-8 w-8 absolute top-4 right-4"
              disabled={deleting}
            >
              <X className="h-4 w-4" />
            </Button>

            <div className="p-8 text-center">
              <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-2xl flex items-center justify-center mx-auto mb-5">
                <Trash2 className="h-8 w-8 text-red-600 dark:text-red-400" />
              </div>

              <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                {t('deleteConfirmTitle')}
              </h2>

              <p className="text-sm text-gray-600 dark:text-gray-400 mb-6">
                {t('deleteConfirmMessage', { count: selectedIds.size })}
              </p>

              <div className="flex gap-3">
                <Button
                  onClick={() => setShowDeleteModal(false)}
                  disabled={deleting}
                  variant="outline"
                  className="flex-1"
                >
                  {t('cancel')}
                </Button>
                <Button
                  onClick={handleConfirmDelete}
                  disabled={deleting}
                  variant="destructive"
                  className="flex-1"
                >
                  {deleting ? (
                    <>
                      <LoadingSpinner size="xs" />
                      {t('deleting')}
                    </>
                  ) : (
                    t('deleteSelected')
                  )}
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
