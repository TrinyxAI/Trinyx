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
/** The id of the resource a DETAIL page is showing, when the test is on one. */
let mockDetailId: string | null = null;
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({
    view: mockView,
    workflowId: mockDetailId,
    dataSourceId: mockDetailId,
    interfaceId: mockDetailId,
    publicationId: mockDetailId,
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
  publicationService: {
    getPublicationById: vi.fn().mockResolvedValue({}),
    getFavoriteIds: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProject: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

import { emitResourceFolderTrail } from '@/lib/folders/foldersHeaderBus';
import { useBreadcrumbs } from '../useBreadcrumbs';

/**
 * A folder crumb changes `?folder=` on the page already on screen, which goes through the
 * history API and not the router - a router push of the bare pathname is dropped when the page
 * was loaded straight into a folder, and the crumb then does nothing at all.
 */
const pushState = vi.fn();
const originalPushState = window.history.pushState;

beforeEach(() => {
  mockPathname = '/en/app/workflow';
  mockSearch = new URLSearchParams();
  mockView = 'workflow';
  mockDetailId = null;
  navigate.mockClear();
  pushState.mockClear();
  window.history.pushState = pushState as unknown as typeof window.history.pushState;
});
afterEach(() => {
  window.history.pushState = originalPushState;
  cleanup();
});

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
    mockSearch = new URLSearchParams('folder=f2');
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

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow?folder=f1');
    expect(navigate).not.toHaveBeenCalled();
  });

  it("the list's own crumb takes the address back to the top level, not to its own path", () => {
    // The page was opened DIRECTLY on a folder, which is the case that used to strand the
    // user: routing to the list's path leaves the address untouched (the pathname is already
    // that one) and the crumb did nothing at all.
    mockSearch = new URLSearchParams('folder=f2');
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({
        view: 'workflow',
        trail: [{ id: 'f1', name: 'Marketing' }, { id: 'f2', name: 'Q4' }],
      });
    });
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow');
    expect(navigate).not.toHaveBeenCalled();
  });

  it("the list's own crumb has nowhere to go from the plain list, and does nothing", () => {
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    // Already at the address it points to: writing it again would only cost an extra Back.
    expect(navigate).not.toHaveBeenCalled();
    expect(pushState).not.toHaveBeenCalled();
  });

  it("the list's own crumb routes when the list is a DIFFERENT page from the one on screen", () => {
    mockPathname = '/en/app/workflow/w1';
    mockDetailId = 'w1';
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(navigate).toHaveBeenCalledWith('/en/app/workflow');
    expect(pushState).not.toHaveBeenCalled();
  });

  it('every other parameter the page carries survives leaving a folder', () => {
    mockSearch = new URLSearchParams('q=alpha&page=2&folder=f1');
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'workflow', trail: [{ id: 'f1', name: 'Marketing' }] });
    });
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow?q=alpha&page=2');
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
    mockSearch = new URLSearchParams('folder=f9');
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'agent', trail: [{ id: 'f9', name: 'Support crew' }] });
    });

    expect(labels(result.current.breadcrumbItems)).toEqual(['Home', 'Agents', 'Support crew']);
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });
    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/agent');
  });
});

/**
 * A list's own crumb has to tell two situations apart: standing in a folder of the list
 * (where it goes back to the top LEVEL, on the same page) and standing anywhere else (where it
 * is an ordinary link to the list). The trail is broadcast by the list and is not cleared the
 * instant a detail page opens, so "is a folder open" cannot be read from the trail alone.
 */
describe('useBreadcrumbs - the list crumb on a DETAIL page', () => {
  it.each([
    ['workflow', '/en/app/workflow/w1', '/en/app/workflow'],
    ['table', '/en/app/tables/t1', '/en/app/tables'],
    ['interface', '/en/app/interface/i1', '/en/app/interface'],
  ])('a %s detail page routes to the list instead of pushing a folder onto its own address', (view, pathname, listPath) => {
    mockView = view === 'table' ? 'data' : view;
    mockPathname = pathname;
    mockDetailId = 'detail-1';
    const { result } = renderHook(() => useBreadcrumbs());

    // A trail left behind by the list the user came from.
    act(() => {
      emitResourceFolderTrail({
        view: view as 'workflow',
        trail: [{ id: 'f1', name: 'Marketing' }],
      });
    });
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(navigate).toHaveBeenCalledWith(listPath);
    // Never a `?folder=` on the detail page's own address, which would render the list's
    // level under a URL that names one workflow.
    expect(pushState).not.toHaveBeenCalled();
  });

  it('does not print the folder path beside a detail page', () => {
    mockPathname = '/en/app/workflow/w1';
    mockDetailId = 'detail-1';
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'workflow', trail: [{ id: 'f1', name: 'Marketing' }] });
    });

    expect(labels(result.current.breadcrumbItems)).not.toContain('Marketing');
  });
});

describe('useBreadcrumbs - lists whose own crumb is inert at the top level', () => {
  it('leaves the Agents crumb unclickable when no folder is open', () => {
    mockView = 'agent';
    mockPathname = '/en/app/agent';
    const { result } = renderHook(() => useBreadcrumbs());

    expect(labels(result.current.breadcrumbItems)).toEqual(['Home', 'Agents']);
    expect(result.current.breadcrumbItems[1].onClick).toBeUndefined();
  });

  it('leaves the Applications crumb unclickable when no folder is open', () => {
    mockView = 'applications';
    mockPathname = '/en/app/applications';
    const { result } = renderHook(() => useBreadcrumbs());

    expect(result.current.breadcrumbItems[1].onClick).toBeUndefined();
  });

  it('makes the Applications crumb go back to the top level once a folder is open', () => {
    mockView = 'applications';
    mockPathname = '/en/app/applications';
    mockSearch = new URLSearchParams('folder=f1');
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'application', trail: [{ id: 'f1', name: 'Launch apps' }] });
    });
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/applications');
  });

  it('makes the Applications crumb route to the list from an application DETAIL page', () => {
    mockView = 'applications';
    mockPathname = '/en/app/applications/p1';
    mockDetailId = 'p1';
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(navigate).toHaveBeenCalledWith('/en/app/applications');
  });
});

describe('useBreadcrumbs - when the trail and the address disagree', () => {
  it('stays clickable while it is the LAST crumb, or there is no way out at all', () => {
    // Until the trail lands there is no folder segment after this one, so it IS the last
    // crumb - which the header renders inert. The in-page path is absent for the same reason,
    // leaving the browser's Back button as the only escape.
    mockSearch = new URLSearchParams('folder=f1');
    const { result } = renderHook(() => useBreadcrumbs());

    expect(result.current.breadcrumbItems[1].alwaysClickable).toBe(true);
  });

  it('is an ordinary crumb once the address is out of the folder', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    expect(result.current.breadcrumbItems[1].alwaysClickable).toBeUndefined();
  });

  it('gets out of the folder even before the list has said what it is called', () => {
    // A cold load on `?folder=X` renders the header BEFORE the list broadcasts its trail, so
    // for that moment the crumb has no folder path to go on. It must still work: this is the
    // very shape the whole change exists for - a page opened directly inside a folder.
    mockSearch = new URLSearchParams('q=alpha&folder=f1');
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    // The same exit the named crumb takes: only the folder goes, the search stays.
    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/workflow?q=alpha');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not offer to leave a folder the address is no longer in', () => {
    // The trail is a repaint of NAMES broadcast by the list; it can still be on screen for a
    // moment after the address has left the folder. Reading it as "a folder is open" would put
    // the crumb back into the state this change exists to remove: a click that resolves to the
    // address it is already on, and so does nothing at all.
    mockSearch = new URLSearchParams();
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      emitResourceFolderTrail({ view: 'workflow', trail: [{ id: 'f1', name: 'Marketing' }] });
    });
    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(pushState).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});

/**
 * The list crumb is not the only one that says "take me back to the plain page". The agents
 * list keeps its Fleet, Metrics and Skills views in `?view=`, on the SAME pathname, so its
 * crumb drops a query exactly the way leaving a folder does - and was dropped by the router
 * for exactly the same reason.
 */
describe('useBreadcrumbs - a list whose views live in the query', () => {
  it.each(['fleet', 'metrics', 'skills'])('leaves the %s view of the agents list', (view) => {
    mockView = 'agent';
    mockPathname = '/en/app/agent';
    mockSearch = new URLSearchParams(`view=${view}`);
    const { result } = renderHook(() => useBreadcrumbs());

    act(() => {
      result.current.breadcrumbItems[1].onClick?.();
    });

    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/agent');
    expect(navigate).not.toHaveBeenCalled();
  });
});
