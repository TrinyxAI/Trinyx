// @vitest-environment jsdom
/**
 * The folder path in the app header (V448-V452).
 *
 * A list keeps the open folder in the address, but only the list knows what those folder ids
 * are CALLED - so it broadcasts the names and the header prints them. What is pinned here:
 * the path appears after the list's own crumb, the last crumb is the page itself (so it does
 * not navigate), an intermediate crumb navigates by changing the address, and a path
 * broadcast by ANOTHER list is ignored.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, cleanup, act } from '@testing-library/react';

let mockPathname = '/en/app/workflow';
let mockSearch = new URLSearchParams();

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useSearchParams: () => mockSearch,
}));
let mockView = 'workflow';
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({
    view: mockView, workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null,
  }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false }),
}));
const navigate = vi.fn();
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => navigate,
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getDataSources: vi.fn().mockResolvedValue([]),
    getWorkflow: vi.fn().mockResolvedValue({}),
    getInterface: vi.fn().mockResolvedValue({}),
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProject: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

import { emitResourceFolderTrail } from '@/lib/folders/foldersHeaderBus';
import { useBreadcrumbs } from '../useBreadcrumbs';

beforeEach(() => {
  mockPathname = '/en/app/workflow';
  mockSearch = new URLSearchParams();
  mockView = 'workflow';
  navigate.mockClear();
});
afterEach(() => cleanup());

/** The home crumb is an icon, so its label is empty - name it for readability. */
const labels = (items: Array<{ label: string }>) =>
  items.map((item, index) => (index === 0 && item.label === '' ? 'Home' : item.label));

describe('useBreadcrumbs - the folder path of a resource list', () => {
  it('shows nothing extra at the top level', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    expect(labels(result.current.breadcrumbItems)).toEqual(['Home', 'Workflows']);
  });

  it('continues the path with the folders the list is inside', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({
        view: 'workflow',
        trail: [{ id: 'f1', name: 'Marketing' }, { id: 'f2', name: 'Q4' }],
      });
    });

    expect(labels(result.current.breadcrumbItems)).toEqual(['Home', 'Workflows', 'Marketing', 'Q4']);
  });

  it('the last crumb is the page being shown, so it does not navigate', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({
        view: 'workflow',
        trail: [{ id: 'f1', name: 'Marketing' }, { id: 'f2', name: 'Q4' }],
      });
    });

    const items = result.current.breadcrumbItems;
    expect(items[items.length - 1].onClick).toBeUndefined();
  });

  it('an intermediate crumb navigates by changing the address', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({
        view: 'workflow',
        trail: [{ id: 'f1', name: 'Marketing' }, { id: 'f2', name: 'Q4' }],
      });
    });
    act(() => {
      result.current.breadcrumbItems[2].onClick?.();
    });

    expect(navigate).toHaveBeenCalledWith('/en/app/workflow?folder=f1');
  });

  it('a path broadcast by another list is ignored', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'agent', trail: [{ id: 'f9', name: 'Support crew' }] });
    });

    expect(labels(result.current.breadcrumbItems)).toEqual(['Home', 'Workflows']);
  });

  it('prints the path of the agents list too, and its own crumb goes back to the top level', () => {
    mockView = 'agent';
    mockPathname = '/en/app/agent';
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'agent', trail: [{ id: 'f9', name: 'Support crew' }] });
    });

    expect(labels(result.current.breadcrumbItems)).toEqual(['Home', 'Agents', 'Support crew']);
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });
    expect(navigate).toHaveBeenCalledWith('/en/app/agent');
  });
});
