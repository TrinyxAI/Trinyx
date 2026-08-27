'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { orchestratorApi, type GenerationModel } from '@/lib/api/orchestrator';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import { CredentialSection, type CredentialSource } from '../CredentialSection';
import { UpgradeRequiredBadge, UpgradeRequiredNotice } from '@/components/billing/UpgradeRequiredBadge';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import {
  platformQuantityFor,
  GENERATE_FILE_PARAMS,
  GENERATE_NUMERIC_PARAMS,
  GENERATE_PARAM_KEYS,
  isCredentialSource,
  DEFAULT_CREDENTIAL_SOURCE,
} from '../../../utils/generateParams';

interface GenerateParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Form component for the Generate node.
 *
 * <p>The model is picked first and everything else follows from it: only the
 * parameters that model declares are offered, each with its own limits, and the
 * price shown is that model's rate applied to the request currently typed. The
 * price is not decoration: a generation is charged per run, and a per-second
 * model costs ten times more for a ten second clip than for a one second one.
 */
export function GenerateParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: GenerateParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');

  const model: string = (data as any).generateModel ?? '';
  const params: Record<string, any> = React.useMemo(
    () => (data as any).generateParams ?? {},
    [data],
  );
  // A generate node defaults to the platform's key: that is the arrangement the
  // price quote below describes, and switching to your own key is the explicit
  // opt out (the platform then bills nothing for this node).
  const credentialSource: CredentialSource = isCredentialSource((data as any).generateCredentialSource)
    ? (data as any).generateCredentialSource
    : DEFAULT_CREDENTIAL_SOURCE;

  // Only the platform key spends credits: a node set to the reader's own key
  // is billed by the provider directly, so a badge there would be a lie.
  const { blocked: creditsCannotPay } = useMonthlyCreditsCannotPay();
  const generationBlocked = creditsCannotPay && credentialSource === 'platform';

  const { data: catalog, isLoading: isLoadingModels } = useQuery({
    queryKey: ['generation-models'],
    // Models change only on a catalog import, so this is cached for the session
    // rather than re-fetched on every inspector open.
    queryFn: () => orchestratorApi.getGenerationModels(),
    staleTime: 5 * 60_000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  const models: GenerationModel[] = catalog?.models ?? [];
  const selected = React.useMemo(
    () => models.find((m) => m.model === model) ?? null,
    [models, model],
  );

  const update = React.useCallback((patch: Record<string, unknown>) => {
    if (isRunMode) return;
    onUpdate({ ...data, ...patch } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const setParam = React.useCallback((key: string, value: unknown) => {
    if (isRunMode) return;
    const next = { ...params };
    if (value === undefined || value === null || value === '') {
      delete next[key];
    } else {
      next[key] = value;
    }
    update({ generateParams: next });
  }, [isRunMode, params, update]);

  const handleModelChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    // Drop the parameters the new model does not accept rather than carrying
    // them over: sending one is refused, and a form that keeps showing a value
    // the model rejects reads as if it were still in effect.
    const next = models.find((m) => m.model === value) ?? null;
    const accepted = new Set(next?.accepts ?? []);
    const kept: Record<string, any> = {};
    for (const [key, v] of Object.entries(params)) {
      if (accepted.has(key)) kept[key] = v;
    }
    // The pinned key goes with the model. A credential belongs to ONE provider
    // and two models of the same format routinely come from two, so carrying it
    // over saves an id that can never apply: the run refuses it and falls back,
    // and the node then fails for a missing key rather than for the stale pin.
    // The picker cannot recover it either, since it only re-picks when the new
    // provider already has a key configured.
    update({ generateModel: value, generateParams: kept, selectedCredentialId: null });
  }, [isRunMode, models, params, update]);

  const handleCredentialSourceChange = React.useCallback((source: CredentialSource) => {
    // Moving to the platform's key drops the pin with it: it names one of the
    // AUTHOR's keys, which the platform branch never consults, so leaving it in
    // the plan states a choice no run can honour.
    update({
      generateCredentialSource: source,
      ...(source === 'platform' ? { selectedCredentialId: null } : {}),
    });
  }, [update]);

  // The size of this request in PLATFORM units (seconds, assets, characters),
  // the same measurement the billing path sends. It is quoted, not priced here:
  // the published rate decides what it is charged per, and the quote answers
  // with the unit it used, so the estimate cannot state a unit the invoice
  // disagrees with.
  const quantity = React.useMemo(
    () => platformQuantityFor(selected?.price?.unit, params, selected?.defaultQuantity),
    [selected, params],
  );

  // Only the parameters the selected model declares, in the form's own order.
  const visibleParams = React.useMemo(() => {
    if (!selected) return [] as string[];
    const accepted = new Set(selected.accepts);
    return GENERATE_PARAM_KEYS.filter((key) => accepted.has(key));
  }, [selected]);

  const requiredParams = React.useMemo(
    () => new Set(selected?.required ?? []),
    [selected],
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Model (required) - everything else follows from it */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('generate.model')}
            </span>
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                >
                  <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                  <p className="font-semibold text-slate-900 dark:text-slate-100">{t('generate.title')}</p>
                  <p>{t('generate.description')}</p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">{t('generate.modelHint')}</p>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <Select value={model || undefined} onValueChange={handleModelChange} disabled={isRunMode}>
          <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
            <SelectValue placeholder={isLoadingModels ? t('generate.modelsLoading') : t('generate.modelPlaceholder')} />
          </SelectTrigger>
          <SelectContent>
            {models.map((m) => (
              <SelectItem key={m.model} value={m.model} className="text-sm">
                <span className="flex items-center gap-1.5">
                  <span>{m.label} ({m.kind})</span>
                  {/* The node's own flat fee is covered by the Free plan's
                      monthly credits; the generation it runs on the platform's
                      key is not, and that is the part that gets refused. Only
                      on the platform key: a node set to the reader's own key is
                      never billed in credits. */}
                  {/* Per MODEL: one with no platform credential behind it can
                      only run on the reader's own key, so it costs no credits
                      and a lock on its row would be a lie. This list carries
                      the credential, not the published rate; whether a rate
                      exists is answered by the quote in the section below. */}
                  <UpgradeRequiredBadge blocked={generationBlocked && m.integrationName != null} />
                </span>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {/* Under the picker, not in its options: a listbox option cannot host
            a control. The node's own flat fee IS covered by the monthly
            credits; what is not is the generation it runs on the platform's
            key, which is what gets refused. */}
        <UpgradeRequiredNotice blocked={generationBlocked} className="mt-1.5" />
        {!isLoadingModels && models.length === 0 && (
          <span className="text-xs text-slate-400 dark:text-slate-500">{t('generate.noModels')}</span>
        )}
      </div>

      {/* Per-model parameters. Rendered only for the parameters this model
          accepts, so a value it would refuse cannot be entered here at all. */}
      {selected && visibleParams.map((key) => {
        const limit = selected.limits?.[key];
        // Only a list the platform ENFORCES becomes a closed choice here. An
        // advisory one (values the catalogue knows the provider documents, which
        // nothing checks) must not take the expression field away: a workflow
        // binds a parameter to runtime data far more often than it types one,
        // and a saved node holding a template would read as unset against a
        // list that does not contain it.
        const allowed = limit?.allowedEnforced === false ? undefined : limit?.allowed;
        const value = params[key];
        const isRequired = requiredParams.has(key);
        const isFile = GENERATE_FILE_PARAMS.includes(key);
        const isNumeric = GENERATE_NUMERIC_PARAMS.includes(key);

        return (
          <div key={key} className="flex flex-col gap-1.5">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                {t(`generate.params.${key}`)}
              </span>
              {isRequired && (
                <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
              )}
            </div>

            {allowed && allowed.length > 0 ? (
              <Select
                value={value !== undefined && value !== null ? String(value) : undefined}
                onValueChange={(v) => setParam(key, v)}
                disabled={isRunMode}
              >
                <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
                  <SelectValue placeholder={t('generate.selectValue')} />
                </SelectTrigger>
                <SelectContent>
                  {allowed.map((option) => (
                    <SelectItem key={String(option)} value={String(option)} className="text-sm">
                      {String(option)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            ) : isFile ? (
              <ExpressionEditor
                value={typeof value === 'string' ? value : ''}
                onChange={(v) => setParam(key, v)}
                placeholder={t('generate.filePlaceholder')}
                className="w-full"
                unknownVariables={findUnknownVariables({ [key]: typeof value === 'string' ? value : '' })}
                handleId={`generate-${key}-${node.id}`}
                connections={connectionProps.connections}
                onHandleClick={connectionProps.handleHandleClick}
                draggingFromHandle={connectionProps.draggingFromHandle}
                onHandleMouseDown={connectionProps.handleHandleMouseDown}
                onHandleMouseUp={connectionProps.handleHandleMouseUp}
              />
            ) : isNumeric ? (
              <Input
                type="number"
                value={value !== undefined && value !== null ? String(value) : ''}
                min={limit?.min}
                max={limit?.max}
                onChange={(e) => {
                  const raw = e.target.value;
                  setParam(key, raw === '' ? '' : Number(raw));
                }}
                disabled={isRunMode}
                className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5"
              />
            ) : (
              <ExpressionEditor
                value={typeof value === 'string' ? value : ''}
                onChange={(v) => setParam(key, v)}
                placeholder={t(`generate.placeholders.${key}`)}
                className="w-full"
                unknownVariables={findUnknownVariables({ [key]: typeof value === 'string' ? value : '' })}
                handleId={`generate-${key}-${node.id}`}
                connections={connectionProps.connections}
                onHandleClick={connectionProps.handleHandleClick}
                draggingFromHandle={connectionProps.draggingFromHandle}
                onHandleMouseDown={connectionProps.handleHandleMouseDown}
                onHandleMouseUp={connectionProps.handleHandleMouseUp}
              />
            )}

            {(limit?.min !== undefined || limit?.max !== undefined) && (
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {t('generate.limitRange', {
                  min: limit?.min !== undefined ? String(limit.min) : '-',
                  max: limit?.max !== undefined ? String(limit.max) : '-',
                })}
              </span>
            )}
          </div>
        );
      })}

      {/*
        Credential source AND the price. The same section the MCP step inspector
        uses, quoted for this MODEL and this request size, so the user reads the
        real cost of the run they just configured ("60 credits per second, 10
        second = 600 credits") before they run it. Switching to their own key
        hides the rate, which is correct: the platform then bills nothing.
      */}
      {selected && selected.integrationName && (
        <CredentialSection
          toolCredentials={[{
            credentialName: selected.integrationName,
            isRequired: true,
            displayName: selected.provider,
          }]}
          selectedCredentialId={(data as any).selectedCredentialId ?? null}
          onCredentialSelect={(credentialId) => update({ selectedCredentialId: credentialId })}
          integration={selected.integrationName}
          apiToolId={selected.apiToolId}
          modelId={selected.model}
          quantity={quantity}
          // What this call is COUNTED in, so the quote can refuse a rate that
          // cannot price it at all: a rate published per image against a call
          // counted in seconds shows a number, and then every run of that model
          // is refused. Not the unit it is SOLD by, which may legitimately
          // differ in scale (published per minute, counted in seconds).
          quantityUnit={selected.measuredUnit}
          // Every row of this catalogue is a generation, so say it outright
          // rather than leaving it to be inferred from the model id: a blank
          // model would otherwise have the quote fall back to the
          // credential-wide default, which is not a price for a generation.
          isGeneration
          isRunMode={isRunMode}
          credentialSource={credentialSource}
          onCredentialSourceChange={handleCredentialSourceChange}
        />
      )}
    </div>
  );
}
