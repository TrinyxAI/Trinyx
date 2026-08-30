// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { EditColumnModal } from '../modals/EditColumnModal';
import { COLUMN_STYLE_PRESETS, renderPresetPreview } from '../visualHelpers';

/**
 * The two media presets create ONE column type and differ only by `display.render`. That makes
 * every lookup keyed on the visual type alone ambiguous, and makes the render a safe thing to
 * change on an existing column - neither of which was true when they were separate types.
 */

const baseColumn = {
  field: 'data.photo',
  header_name: 'photo',
  type: 'file' as const,
};

function renderModal(column: Record<string, unknown>, onSave = vi.fn()) {
  render(
    <EditColumnModal
      isOpen
      isSaving={false}
      column={column as never}
      onClose={() => {}}
      onSave={onSave}
    />,
  );
  return onSave;
}

afterEach(() => cleanup());

describe('the media presets share one visual type', () => {
  it('both create the same column type, so only the render tells them apart', () => {
    const media = COLUMN_STYLE_PRESETS.filter((p) => p.id === 'file' || p.id === 'image');

    expect(media).toHaveLength(2);
    expect(new Set(media.map((p) => p.visualType))).toEqual(new Set(['file']));
    expect(media.map((p) => p.display?.render).sort()).toEqual(['card', 'thumbnail']);
  });
});

describe('EditColumnModal recap for a media column', () => {
  it('shows the thumbnail preset for a column rendered as a thumbnail', () => {
    // Regression: the recap was looked up by visual type alone. Once both media presets became
    // `file`, a column the user created as "Image" re-opened showing the "Attachment" recap.
    renderModal({ ...baseColumn, displayConfig: { render: 'thumbnail' } });

    expect(screen.getByText('types.image.label')).toBeInTheDocument();
    expect(screen.queryByText('types.file.label')).not.toBeInTheDocument();
  });

  it('shows the card preset for a column rendered as a card', () => {
    renderModal({ ...baseColumn, displayConfig: { render: 'card' } });

    expect(screen.getByText('types.file.label')).toBeInTheDocument();
  });

  it('treats a legacy image column with no display.render as a thumbnail', () => {
    // Columns created before the merge carry type 'image' and no render at all.
    renderModal({ ...baseColumn, type: 'image', displayConfig: {} });

    expect(screen.getByText('types.image.label')).toBeInTheDocument();
  });
});

describe('changing how a media column is drawn', () => {
  it('offers the choice on an existing column', () => {
    // Changing an actual type is still refused because it would invalidate stored values.
    // The render cannot: every media column holds the same asset either way.
    renderModal({ ...baseColumn, displayConfig: { render: 'card' } });

    expect(screen.getByText('mediaRender.label')).toBeInTheDocument();
  });

  it('submits only the render, leaving the rest of the display untouched', () => {
    const onSave = renderModal({
      ...baseColumn,
      displayConfig: { render: 'card', label: 'Attachment' },
    });

    fireEvent.change(screen.getByDisplayValue('mediaRender.card'), { target: { value: 'thumbnail' } });
    fireEvent.click(screen.getByText('save'));

    expect(onSave).toHaveBeenCalledWith({
      display: expect.objectContaining({ render: 'thumbnail', label: 'Attachment' }),
    });
  });

  it('cannot be saved when nothing changed', () => {
    const onSave = renderModal({ ...baseColumn, displayConfig: { render: 'card' } });

    fireEvent.click(screen.getByText('save'));

    expect(onSave).not.toHaveBeenCalled();
  });
});

describe('the recap card is a promise about the column, so it follows the control', () => {
  it('switches to the thumbnail recap as soon as the user picks Thumbnail', () => {
    // It was built from the SAVED display, so the card kept describing a card layout while the
    // user had already chosen a thumbnail - the one place that is supposed to show what you are
    // about to get.
    renderModal({ ...baseColumn, displayConfig: { render: 'card' } });
    expect(screen.getByText('types.file.label')).toBeInTheDocument();

    fireEvent.change(screen.getByDisplayValue('mediaRender.card'), { target: { value: 'thumbnail' } });

    expect(screen.getByText('types.image.label')).toBeInTheDocument();
    expect(screen.queryByText('types.file.label')).not.toBeInTheDocument();
  });

  it('switches back', () => {
    renderModal({ ...baseColumn, type: 'image', displayConfig: {} });
    expect(screen.getByText('types.image.label')).toBeInTheDocument();

    fireEvent.change(screen.getByDisplayValue('mediaRender.thumbnail'), { target: { value: 'card' } });

    expect(screen.getByText('types.file.label')).toBeInTheDocument();
  });

  it('writes an explicit render onto a legacy image column that had none', () => {
    // The legacy column stores no render; its look comes from a type alias resolved at render
    // time. Materialising the value is what keeps it pinned the day that alias goes away.
    const onSave = renderModal({ ...baseColumn, type: 'image', displayConfig: {} });

    fireEvent.change(screen.getByDisplayValue('mediaRender.thumbnail'), { target: { value: 'card' } });
    fireEvent.click(screen.getByText('save'));

    expect(onSave).toHaveBeenCalledWith({ display: expect.objectContaining({ render: 'card' }) });
  });
});

describe('a media preview is a rounded square, in both places', () => {
  it('the modal preview is not a circle', () => {
    // The cell and this preview are one promise; the change states they must not drift, so the
    // shape is asserted rather than left to a reviewer's eye.
    const { container } = render(<div>{renderPresetPreview(
      COLUMN_STYLE_PRESETS.find((p) => p.id === 'image')!,
    )}</div>);

    expect(container.querySelector('.rounded-xl')).not.toBeNull();
    expect(container.querySelector('.rounded-full')).toBeNull();
  });
});

describe('the save button obeys "nothing changed" for media columns too', () => {
  it('is inert on a legacy image column nobody has touched', () => {
    // The legacy column stores no render, so comparing against the STORED value marked it dirty
    // on mount: Save was live before any interaction and fired a write nobody asked for.
    const onSave = renderModal({ ...baseColumn, type: 'image', displayConfig: {} });

    fireEvent.click(screen.getByText('save'));

    expect(onSave).not.toHaveBeenCalled();
  });

  it('is inert on a modern column nobody has touched', () => {
    const onSave = renderModal({ ...baseColumn, displayConfig: { render: 'thumbnail' } });

    fireEvent.click(screen.getByText('save'));

    expect(onSave).not.toHaveBeenCalled();
  });
});
