"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, ChevronRight, Plus, Trash2 } from "lucide-react";
import { useTranslations } from "next-intl";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { credentialService } from "@/lib/api/orchestrator/credential.service";
import {
  catalogVisibilityService,
  type IntegrationVisibility,
  type ToolVisibility,
} from "@/lib/api/services/catalog-visibility.service";
import { generationService, type GenerationModel } from "@/lib/api/orchestrator/generation.service";
import type { PlatformCredential, PricingEntry, PricingVersion } from "@/lib/api/orchestrator/types";
import { formatUtcDateTime } from "@/lib/utils/dateFormatters";

/**
 * Units a price can be charged per. Mirrors the backend `PriceUnit` enum; an
 * unknown value coming back from an older or newer backend is rendered verbatim
 * rather than mistranslated.
 */
const PRICE_UNITS = ["call", "second", "minute", "image", "character"] as const;

/** Sentinel for "no model", since a Select cannot carry an empty value. */
const ENDPOINT_WIDE = "__endpoint__";

/** One editable price row: an endpoint, optionally a model, and five numbers. */
interface PriceRow {
  id: string;
  apiToolId: string;
  /** Empty string = the endpoint-wide price. */
  modelId: string;
  priceUnit: string;
  baseCredits: string;
  unitCredits: string;
  minCredits: string;
  maxCredits: string;
}

function genId(): string {
  return Math.random().toString(36).slice(2);
}

function blankRow(): PriceRow {
  return {
    id: genId(),
    apiToolId: "",
    modelId: "",
    priceUnit: "call",
    baseCredits: "0.00",
    unitCredits: "0",
    minCredits: "",
    maxCredits: "",
  };
}

/**
 * Every price of a version, whatever the backend could tell us.
 *
 * A backend that predates per-model pricing only sends `overrides`, one flat
 * amount per endpoint; that shape maps exactly onto a `call` row with no rate
 * and no clamps, so the history renders the same either way.
 */
export function versionPrices(version: PricingVersion): PricingEntry[] {
  if (version.prices) return version.prices;
  return Object.entries(version.overrides ?? {}).map(([apiToolId, amount]) => ({
    apiToolId,
    modelId: null,
    priceUnit: "call",
    baseCredits: String(amount),
    unitCredits: "0",
    minCredits: null,
    maxCredits: null,
  }));
}

function isPositive(value: string | null | undefined): boolean {
  if (value == null || value === "") return false;
  const n = Number(value);
  return Number.isFinite(n) && n > 0;
}

/**
 * A price as a human reads it, not as an opaque number.
 *
 * "60 credits per second" and "600 credits per call" are different promises, and
 * an admin pricing a video model has no way to tell them apart from the amount
 * alone. Exported so the branch itself is testable: getting it wrong states a
 * price the customer is never charged.
 */
export function describePrice(
  price: Pick<PricingEntry, "priceUnit" | "baseCredits" | "unitCredits" | "minCredits" | "maxCredits">,
  t: (key: string, values?: Record<string, string>) => string,
): string {
  const unit = (PRICE_UNITS as readonly string[]).includes(price.priceUnit)
    ? t(`units.${price.priceUnit}`)
    : price.priceUnit;
  const rate = price.unitCredits ?? "0";
  const base = price.baseCredits ?? "0";

  let sentence: string;
  if (!isPositive(rate)) {
    sentence = t("summaryFlat", { amount: String(Number(base) || 0) });
  } else if (isPositive(base)) {
    sentence = t("summaryBasePlusUnit", { base: String(Number(base)), rate: String(Number(rate)), unit });
  } else {
    sentence = t("summaryPerUnit", { rate: String(Number(rate)), unit });
  }

  const notes: string[] = [];
  if (price.minCredits != null && price.minCredits !== "") {
    notes.push(t("floorNote", { min: String(Number(price.minCredits)) }));
  }
  if (price.maxCredits != null && price.maxCredits !== "") {
    notes.push(t("ceilingNote", { max: String(Number(price.maxCredits)) }));
  }
  return notes.length === 0 ? sentence : `${sentence}, ${notes.join(", ")}`;
}

interface PricingModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  credential: PlatformCredential | null;
  onNotify: (type: "success" | "error" | "info", title: string, message?: string) => void;
}

export function PricingModal({ open, onOpenChange, credential, onNotify }: PricingModalProps) {
  const credentialId = credential?.id;
  const t = useTranslations("platformCredentials.pricing");
  const tSettings = useTranslations("settings");
  const onNotifyRef = useRef(onNotify);

  const [versions, setVersions] = useState<PricingVersion[]>([]);
  const [tools, setTools] = useState<ToolVisibility[]>([]);
  const [models, setModels] = useState<GenerationModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [expandedVersionId, setExpandedVersionId] = useState<number | null>(null);
  // The published prices could not be read. Publishing REPLACES the whole list,
  // so an empty form on top of an unknown live list would delete every row.
  const [loadFailed, setLoadFailed] = useState(false);
  // Identifies the load in flight. A load that is no longer the current one
  // writes nothing: reopening on another credential while the first is still
  // running would otherwise leave that credential's rows in this form, and the
  // next publish would write them under the wrong credential.
  const loadTokenRef = useRef(0);

  // Empty string = "no API-wide default". Per-tool prices then carry the
  // whole pricing for this version; tools not listed get a zero markup.
  const [defaultMarkup, setDefaultMarkup] = useState("");
  const [priceRows, setPriceRows] = useState<PriceRow[]>([]);

  useEffect(() => {
    onNotifyRef.current = onNotify;
  }, [onNotify]);

  const toolById = useMemo(() => {
    const m = new Map<string, ToolVisibility>();
    for (const tool of tools) m.set(tool.toolId, tool);
    return m;
  }, [tools]);

  /**
   * Which models each endpoint backs, straight from the generation catalog.
   *
   * The admin cannot price a model without knowing it exists, and the model list
   * already ships with the endpoint id it is executed by, so no new read is
   * needed: the same call the workflow inspector makes answers the question.
   */
  const modelsByTool = useMemo(() => {
    const m = new Map<string, GenerationModel[]>();
    for (const model of models) {
      if (!model.apiToolId) continue;
      const list = m.get(model.apiToolId) ?? [];
      list.push(model);
      m.set(model.apiToolId, list);
    }
    return m;
  }, [models]);

  const loadAll = useCallback(async () => {
    if (!credential || !Number.isFinite(credentialId)) return;
    const token = ++loadTokenRef.current;
    const stale = () => token !== loadTokenRef.current;
    setLoading(true);
    setLoadFailed(false);
    try {
      const [versionsData, integrationsData, modelsData] = await Promise.all([
        // Deliberately NOT swallowed: publishing replaces the live price list,
        // so "the read failed" and "nothing is published" must never look the
        // same. A failure here blocks publishing instead of emptying the form.
        credentialService.listPricingVersions(credentialId!),
        catalogVisibilityService.getIntegrations().catch(() => [] as IntegrationVisibility[]),
        // Generation may be switched off on this deployment, in which case there
        // are no models to price and endpoint-wide pricing is the whole story.
        generationService.getModels().then((r) => r.models).catch(() => [] as GenerationModel[]),
      ]);
      if (stale()) return;
      setVersions(versionsData);
      setModels(modelsData);

      const match = integrationsData.find(
        (v) => v.credentialName?.toLowerCase().trim() === credential.integrationName.toLowerCase().trim(),
      );
      if (match) {
        const toolList = await catalogVisibilityService.getApiTools(match.apiId).catch(() => [] as ToolVisibility[]);
        if (stale()) return;
        setTools(toolList);
      } else {
        setTools([]);
      }

      // Start from what is live: the admin edits the published prices rather
      // than retyping them, which is what stops a republish from dropping rows.
      const latest = versionsData[0];
      if (latest) {
        setDefaultMarkup(latest.defaultMarkupCredits == null ? "" : String(latest.defaultMarkupCredits));
        setPriceRows(versionPrices(latest).map(toRow));
      }
    } catch (err) {
      if (stale()) return;
      setLoadFailed(true);
      setVersions([]);
      setPriceRows([]);
      setDefaultMarkup("");
      onNotifyRef.current("error", t("errors.fetchFailed"), err instanceof Error ? err.message : String(err));
    } finally {
      if (!stale()) setLoading(false);
    }
  }, [credential, credentialId, t]);

  // Reset + reload every time a new credential opens.
  useEffect(() => {
    if (open && credential) {
      setDefaultMarkup("");
      setPriceRows([]);
      setExpandedVersionId(null);
      loadAll();
    }
  }, [open, credential, loadAll]);

  const addPriceRow = () => {
    setPriceRows((rows) => [...rows, blankRow()]);
  };

  const removePriceRow = (id: string) => {
    setPriceRows((rows) => rows.filter((r) => r.id !== id));
  };

  const updatePriceRow = (id: string, patch: Partial<PriceRow>) => {
    setPriceRows((rows) => rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  };

  const copyFromVersion = (v: PricingVersion) => {
    // Null default → leave the field empty so the admin can see at a glance
    // that this version billed purely via per-tool prices.
    setDefaultMarkup(v.defaultMarkupCredits == null ? "" : String(v.defaultMarkupCredits));
    setPriceRows(versionPrices(v).map(toRow));
    onNotify("info", t("copiedFromVersion", { version: v.version }));
  };

  const handlePublish = async () => {
    if (!credentialId) return;
    // A publish sends replace: true, so it deletes every published row this
    // form does not carry. When the read failed the form carries nothing, and
    // publishing would wipe the live price list. Refuse rather than delete.
    if (loadFailed) {
      onNotify("error", t("errors.loadFailed"));
      return;
    }

    // Empty default = "no API-wide rate, only per-tool prices apply". Any other
    // non-numeric / negative input is a typo and gets rejected up-front.
    const trimmedDefault = defaultMarkup.trim();
    let defaultMarkupPayload: string | null = null;
    if (trimmedDefault !== "") {
      const parsed = Number(trimmedDefault);
      if (!Number.isFinite(parsed) || parsed < 0) {
        onNotify("error", t("errors.invalidDefault"));
        return;
      }
      defaultMarkupPayload = String(parsed);
    }

    const seen = new Set<string>();
    const prices: PricingEntry[] = [];
    for (const row of priceRows) {
      const tool = row.apiToolId.trim();
      if (!tool) continue;
      const model = row.modelId.trim();
      const key = `${tool}|${model}`;
      if (seen.has(key)) {
        onNotify("error", t("errors.duplicateTool"));
        return;
      }
      const label = toolById.get(tool)?.toolName?.trim() || tool;
      const amounts = [row.baseCredits, row.unitCredits, row.minCredits, row.maxCredits];
      if (amounts.some((v) => v.trim() !== "" && (!Number.isFinite(Number(v)) || Number(v) < 0))) {
        onNotify("error", t("errors.invalidOverride", { tool: label }));
        return;
      }
      seen.add(key);
      prices.push({
        apiToolId: tool,
        modelId: model === "" ? null : model,
        priceUnit: row.priceUnit,
        baseCredits: String(Number(row.baseCredits || 0)),
        unitCredits: String(Number(row.unitCredits || 0)),
        ...(row.minCredits.trim() === "" ? {} : { minCredits: String(Number(row.minCredits)) }),
        ...(row.maxCredits.trim() === "" ? {} : { maxCredits: String(Number(row.maxCredits)) }),
      });
    }

    // Backend rejects "no default + no prices" (degenerate version), but
    // surface it here too so the admin gets an immediate, clearer error.
    if (defaultMarkupPayload === null && prices.length === 0) {
      onNotify("error", t("errors.invalidDefault"));
      return;
    }

    setPublishing(true);
    try {
      // replace: this form renders and edits EVERY published row, so what the
      // admin sees is what gets published - including a row they deleted, which
      // the backend's carry-forward would otherwise restore.
      const created = await credentialService.publishPricingVersion(credentialId, {
        defaultMarkupCredits: defaultMarkupPayload,
        prices,
        replace: true,
      });
      onNotify("success", t("success"), t("publishedVersion", { version: created.version }));
      await loadAll();
    } catch (err) {
      onNotify(
        "error",
        t("errors.publishFailed"),
        err instanceof Error ? err.message : String(err),
      );
    } finally {
      setPublishing(false);
    }
  };

  const toolLabel = (toolId: string): string =>
    toolById.get(toolId)?.toolName?.trim() ||
    toolById.get(toolId)?.toolSlug?.trim() ||
    t("unknownTool");

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-5xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {t("title")}
            {credential ? ` - ${credential.displayName}` : ""}
          </DialogTitle>
          <p className="text-sm text-theme-secondary">{t("subtitle")}</p>
        </DialogHeader>

        {loading && (
          <div className="py-8 text-center text-sm text-theme-secondary">
            {tSettings("common.loading")}
          </div>
        )}

        {!loading && !credential && (
          <div className="py-8 text-center text-sm text-theme-secondary">
            {t("errors.credentialNotFound")}
          </div>
        )}

        {/* A failed read is shown as a failure, never as "nothing published
            yet": the two states look identical in an empty form, and only one
            of them makes publishing safe. The editor stays closed until the
            live prices are actually known. */}
        {!loading && credential && loadFailed && (
          <div className="py-8 text-center space-y-3">
            <p className="text-sm text-theme-primary">{t("errors.loadFailed")}</p>
            <Button variant="outline" size="sm" onClick={loadAll}>
              {t("retry")}
            </Button>
          </div>
        )}

        {!loading && credential && !loadFailed && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <section className="border border-theme rounded-lg p-4 bg-theme-secondary">
              <h2 className="text-sm font-semibold text-theme-primary mb-3">{t("history")}</h2>
              {versions.length === 0 ? (
                <p className="text-sm text-theme-secondary">{t("noVersions")}</p>
              ) : (
                <ul className="divide-y divide-theme">
                  {versions.map((v) => {
                    const expanded = expandedVersionId === v.id;
                    const published = versionPrices(v);
                    return (
                      <li key={v.id} className="py-2">
                        <button
                          type="button"
                          onClick={() => setExpandedVersionId(expanded ? null : v.id)}
                          className="w-full flex items-center gap-2 text-left"
                        >
                          {expanded ? (
                            <ChevronDown className="w-4 h-4" />
                          ) : (
                            <ChevronRight className="w-4 h-4" />
                          )}
                          <span className="text-sm font-medium text-theme-primary w-12">
                            v{v.version}
                          </span>
                          <span className="text-sm text-theme-secondary flex-1">
                            {t("defaultMarkupLabel")}{" "}
                            {v.defaultMarkupCredits == null ? "-" : String(v.defaultMarkupCredits)}
                            {" · "}
                            {t("overridesLabel", { count: published.length })}
                          </span>
                          <span className="text-xs text-theme-muted">
                            {v.createdAt ? formatUtcDateTime(v.createdAt) : ""}
                          </span>
                        </button>
                        {expanded && (
                          <div className="mt-2 ml-6 space-y-2">
                            {v.createdBy && (
                              <p className="text-xs text-theme-muted">
                                {t("createdBy")}: {v.createdBy}
                              </p>
                            )}
                            {published.length > 0 ? (
                              <ul className="text-sm space-y-2">
                                {published.map((price) => {
                                  const tool = toolById.get(price.apiToolId);
                                  return (
                                    <li
                                      key={`${price.apiToolId}|${price.modelId ?? ""}`}
                                      className="flex items-start gap-2"
                                    >
                                      <div className="flex-1 min-w-0">
                                        <div className="text-sm text-theme-primary font-medium truncate">
                                          {toolLabel(price.apiToolId)}
                                          {price.modelId ? ` · ${price.modelId}` : ""}
                                        </div>
                                        {tool?.description && (
                                          <div className="text-xs text-theme-muted line-clamp-2">
                                            {tool.description}
                                          </div>
                                        )}
                                      </div>
                                      <span className="text-theme-secondary shrink-0">
                                        {price.priceUnit === "call" && !isPositive(price.unitCredits)
                                          ? String(Number(price.baseCredits))
                                          : describePrice(price, t)}
                                      </span>
                                    </li>
                                  );
                                })}
                              </ul>
                            ) : (
                              <p className="text-xs text-theme-muted">{t("noOverrides")}</p>
                            )}
                            <Button variant="outline" size="sm" onClick={() => copyFromVersion(v)}>
                              {t("copyToForm")}
                            </Button>
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>

            <section className="border border-theme rounded-lg p-4 bg-theme-secondary">
              <h2 className="text-sm font-semibold text-theme-primary mb-3">{t("publishNew")}</h2>
              <p className="text-xs text-theme-muted mb-4">{t("warningInFlightRuns")}</p>

              <div className="space-y-3">
                <div>
                  <label className="block text-sm text-theme-primary mb-1">
                    {t("defaultMarkup")}
                  </label>
                  {/* Empty = no API-wide default; per-tool prices then drive the
                      whole pricing. Placeholder renders as - to match how
                      the history list renders a null default. */}
                  <Input
                    type="number"
                    step="0.0001"
                    min="0"
                    value={defaultMarkup}
                    onChange={(e) => setDefaultMarkup(e.target.value)}
                    placeholder="-"
                  />
                </div>

                <div>
                  <div className="flex items-center justify-between mb-2">
                    <label className="block text-sm text-theme-primary">{t("overrides")}</label>
                    <Button variant="ghost" size="sm" onClick={addPriceRow}>
                      <Plus className="w-3.5 h-3.5 mr-1" />
                      {t("addOverride")}
                    </Button>
                  </div>
                  <p className="text-xs text-theme-muted mb-2">{t("modelHint")}</p>
                  {priceRows.length === 0 && (
                    <p className="text-xs text-theme-muted">{t("noOverridesInForm")}</p>
                  )}
                  <div className="space-y-3">
                    {priceRows.map((row) => {
                      const rowModels = modelsByTool.get(row.apiToolId) ?? [];
                      // Keep a model that is already priced selectable even when
                      // the generation catalog cannot be read, so editing an
                      // existing row never silently drops its model.
                      const knownIds = new Set(rowModels.map((m) => m.model));
                      return (
                        <div
                          key={row.id}
                          className="border border-theme rounded-md p-2 space-y-2"
                        >
                          <div className="flex flex-wrap items-start gap-2">
                            <div className="flex-1 min-w-[180px]">
                              <Select
                                value={row.apiToolId || undefined}
                                onValueChange={(v) =>
                                  updatePriceRow(row.id, { apiToolId: v, modelId: "" })
                                }
                              >
                                <SelectTrigger className="rounded-md">
                                  <SelectValue placeholder={t("selectTool")} />
                                </SelectTrigger>
                                <SelectContent>
                                  {tools.map((tool) => (
                                    <SelectItem
                                      key={tool.toolId}
                                      value={tool.toolId}
                                      description={tool.description}
                                    >
                                      {tool.toolName?.trim() ||
                                        tool.toolSlug?.trim() ||
                                        t("unknownTool")}
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                            </div>
                            <div className="flex-1 min-w-[160px]">
                              <Select
                                value={row.modelId === "" ? ENDPOINT_WIDE : row.modelId}
                                onValueChange={(v) =>
                                  updatePriceRow(row.id, { modelId: v === ENDPOINT_WIDE ? "" : v })
                                }
                              >
                                <SelectTrigger className="rounded-md" aria-label={t("model")}>
                                  <SelectValue placeholder={t("modelAny")} />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectItem value={ENDPOINT_WIDE}>{t("modelAny")}</SelectItem>
                                  {rowModels.map((m) => (
                                    <SelectItem key={m.model} value={m.model}>
                                      {m.label} ({m.model})
                                    </SelectItem>
                                  ))}
                                  {row.modelId !== "" && !knownIds.has(row.modelId) && (
                                    <SelectItem value={row.modelId}>{row.modelId}</SelectItem>
                                  )}
                                </SelectContent>
                              </Select>
                            </div>
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => removePriceRow(row.id)}
                              className="shrink-0"
                              aria-label={t("removePrice")}
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </Button>
                          </div>

                          <div className="flex flex-wrap items-end gap-2">
                            <label className="text-xs text-theme-muted">
                              <span className="block mb-1">{t("baseCredits")}</span>
                              <Input
                                type="number"
                                step="0.0001"
                                min="0"
                                value={row.baseCredits}
                                onChange={(e) =>
                                  updatePriceRow(row.id, { baseCredits: e.target.value })
                                }
                                className="w-24"
                              />
                            </label>
                            <label className="text-xs text-theme-muted">
                              <span className="block mb-1">{t("unitCredits")}</span>
                              <Input
                                type="number"
                                step="0.0001"
                                min="0"
                                value={row.unitCredits}
                                onChange={(e) =>
                                  updatePriceRow(row.id, { unitCredits: e.target.value })
                                }
                                className="w-24"
                              />
                            </label>
                            <div className="min-w-[130px]">
                              <span className="block text-xs text-theme-muted mb-1">
                                {t("unit")}
                              </span>
                              <Select
                                value={row.priceUnit}
                                onValueChange={(v) => updatePriceRow(row.id, { priceUnit: v })}
                              >
                                <SelectTrigger className="rounded-md" aria-label={t("unit")}>
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  {PRICE_UNITS.map((u) => (
                                    <SelectItem key={u} value={u}>
                                      {t(`units.${u}`)}
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                            </div>
                            <label className="text-xs text-theme-muted">
                              <span className="block mb-1">{t("minCredits")}</span>
                              <Input
                                type="number"
                                step="0.0001"
                                min="0"
                                value={row.minCredits}
                                onChange={(e) =>
                                  updatePriceRow(row.id, { minCredits: e.target.value })
                                }
                                className="w-20"
                                placeholder="-"
                              />
                            </label>
                            <label className="text-xs text-theme-muted">
                              <span className="block mb-1">{t("maxCredits")}</span>
                              <Input
                                type="number"
                                step="0.0001"
                                min="0"
                                value={row.maxCredits}
                                onChange={(e) =>
                                  updatePriceRow(row.id, { maxCredits: e.target.value })
                                }
                                className="w-20"
                                placeholder="-"
                              />
                            </label>
                          </div>

                          <p className="text-xs text-theme-secondary">
                            {describePrice(
                              {
                                priceUnit: row.priceUnit,
                                baseCredits: row.baseCredits,
                                unitCredits: row.unitCredits,
                                minCredits: row.minCredits,
                                maxCredits: row.maxCredits,
                              },
                              t,
                            )}
                          </p>
                        </div>
                      );
                    })}
                  </div>
                </div>

                <div className="pt-2">
                  <Button onClick={handlePublish} disabled={publishing}>
                    {publishing ? t("publishing") : t("publish")}
                  </Button>
                </div>
              </div>
            </section>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** Published row → editable row. Nulls become the empty strings the inputs use. */
function toRow(price: PricingEntry): PriceRow {
  return {
    id: genId(),
    apiToolId: price.apiToolId,
    modelId: price.modelId ?? "",
    priceUnit: price.priceUnit || "call",
    baseCredits: String(price.baseCredits ?? "0"),
    unitCredits: String(price.unitCredits ?? "0"),
    minCredits: price.minCredits == null ? "" : String(price.minCredits),
    maxCredits: price.maxCredits == null ? "" : String(price.maxCredits),
  };
}
