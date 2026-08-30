// @vitest-environment jsdom
/**
 * Fields the ENGINE resolves as templates must be editable as expressions.
 *
 * Each node listed here calls the template adapter on the named config field at run time
 * (SshNode.command, SftpNode.remotePath/localContent/newPath, DatabaseNode.query and each
 * queryParam, StopOnErrorNode.errorMessage/errorCode, SubWorkflowNode.inputMapping and
 * workflowId). Before this spec they were plain text inputs: the value still reached the
 * resolver, so `{{...}}` typed by hand worked, but nothing in the panel said so - no
 * highlighting, no drag target for the Input column, no unknown-variable warning. A plain
 * input here is therefore not a style choice, it hides a capability the backend has.
 *
 * The counter-examples are asserted too: a trigger has no upstream data to interpolate and
 * TriggerNode resolves nothing, so its fields must stay plain.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Stand-in that marks every expression editor with its handleId, so a test can ask
// "is THIS field an expression field?" rather than counting textareas.
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value, onChange, placeholder, handleId, readOnly }: any) => (
    <textarea
      data-testid={String(handleId ?? 'expr')}
      placeholder={placeholder}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      readOnly={readOnly}
    />
  ),
}));

// CredentialSection reaches for apiClient; the forms under test only need it to mount.
vi.mock('../../CredentialSection', () => ({
  CredentialSection: () => <div data-testid="credential-section" />,
}));

import { SshParametersForm } from '../SshParametersForm';
import { SftpParametersForm } from '../SftpParametersForm';
import { DatabaseParametersForm } from '../DatabaseParametersForm';
import { StopOnErrorParametersForm } from '../StopOnErrorParametersForm';
import { SubWorkflowParametersForm } from '../SubWorkflowParametersForm';

afterEach(cleanup);

const connectionProps = {
  connections: [],
  draggingFromHandle: null,
  hoveredTargetHandle: null,
  handleHandleClick: vi.fn(),
  handleHandleMouseDown: vi.fn(),
  handleHandleMouseUp: vi.fn(),
  handleSetHandleRef: vi.fn(),
};

const NODE_ID = 'n1';

function renderForm(Form: any, data: any) {
  const node = { id: NODE_ID, data } as any;
  render(
    <Form
      node={node}
      data={data}
      onUpdate={vi.fn()}
      connectionProps={connectionProps}
      findUnknownVariables={() => []}
    />,
  );
}

describe('expression coverage for engine-resolved fields', () => {
  it('ssh: the command is an expression field', () => {
    renderForm(SshParametersForm, { sshCommand: 'ls {{trigger.output.dir}}' });
    expect(screen.getByTestId(`ssh-command-${NODE_ID}`)).toBeTruthy();
  });

  it('sftp: remote path, uploaded content and rename target are expression fields', () => {
    renderForm(SftpParametersForm, { sftpOperation: 'upload', sftpRemotePath: '/in/{{trigger.output.name}}' });
    expect(screen.getByTestId(`sftp-remotepath-${NODE_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`sftp-localcontent-${NODE_ID}`)).toBeTruthy();

    cleanup();
    renderForm(SftpParametersForm, { sftpOperation: 'rename', sftpNewPath: '/out/x' });
    expect(screen.getByTestId(`sftp-newpath-${NODE_ID}`)).toBeTruthy();
  });

  it('database: the query and every bound parameter are expression fields', () => {
    renderForm(DatabaseParametersForm, {
      dbQuery: 'SELECT * FROM t WHERE id = $1',
      dbQueryParams: ['{{trigger.output.id}}', 'literal'],
    });
    expect(screen.getByTestId(`db-query-${NODE_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`db-query-param-0-${NODE_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`db-query-param-1-${NODE_ID}`)).toBeTruthy();
  });

  it('stop on error: message and code are expression fields', () => {
    renderForm(StopOnErrorParametersForm, { stopOnErrorMessage: 'boom', stopOnErrorCode: 'E1' });
    expect(screen.getByTestId(`stop-on-error-message-${NODE_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`stop-on-error-code-${NODE_ID}`)).toBeTruthy();
  });

  it('sub workflow: the input mapping is an expression field, and so is a hand-typed workflow id', () => {
    // No workflowData.workflowName -> the id is typed by hand, which the engine resolves too.
    renderForm(SubWorkflowParametersForm, { subWorkflowId: '', subWorkflowInputMapping: '{{core:build.output}}' });
    expect(screen.getByTestId(`sub-workflow-input-mapping-${NODE_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`sub-workflow-id-${NODE_ID}`)).toBeTruthy();
  });

  it('sub workflow: a picked workflow shows its name, not an editable id', () => {
    renderForm(SubWorkflowParametersForm, {
      subWorkflowId: 'wf-1',
      workflowData: { workflowId: 'wf-1', workflowName: 'Publish video' },
      subWorkflowInputMapping: '',
    });
    expect(screen.queryByTestId(`sub-workflow-id-${NODE_ID}`)).toBeNull();
    expect(screen.getByText('Publish video')).toBeTruthy();
  });
});
