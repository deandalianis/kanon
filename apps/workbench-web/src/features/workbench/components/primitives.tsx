import type { ReactNode } from "react";
import type { StageStatus, Tone } from "../types";

function joinClassNames(...values: Array<string | false | null | undefined>) {
  return values.filter(Boolean).join(" ");
}

export function Panel({
  children,
  className
}: {
  children: ReactNode;
  className?: string;
}) {
  return <section className={joinClassNames("wb-panel", className)}>{children}</section>;
}

export function SectionHeader({
  eyebrow,
  title,
  description,
  badge,
  actions
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  badge?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="wb-section-header">
      <div className="wb-section-copy">
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <div className="wb-section-title-row">
          <h2>{title}</h2>
          {badge}
        </div>
        {description && <p className="wb-section-description">{description}</p>}
      </div>
      {actions && <div className="wb-section-actions">{actions}</div>}
    </div>
  );
}

export function StatusBadge({
  children,
  tone = "neutral",
  className
}: {
  children: ReactNode;
  tone?: Tone;
  className?: string;
}) {
  return <span className={joinClassNames("status-badge", `tone-${tone}`, className)}>{children}</span>;
}

export function StageStatusBadge({ status }: { status: StageStatus }) {
  const copy = {
    healthy: "healthy",
    "needs-review": "needs review",
    blocked: "blocked",
    "not-loaded": "not loaded"
  } satisfies Record<StageStatus, string>;

  const tone = {
    healthy: "positive",
    "needs-review": "warning",
    blocked: "danger",
    "not-loaded": "neutral"
  } satisfies Record<StageStatus, Tone>;

  return <StatusBadge tone={tone[status]}>{copy[status]}</StatusBadge>;
}

export function MetricTile({
  label,
  value,
  detail,
  tone = "neutral",
  action
}: {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
  tone?: Tone;
  action?: ReactNode;
}) {
  return (
    <article className={joinClassNames("metric-tile", `tone-${tone}`)}>
      <span className="metric-tile-label">{label}</span>
      <strong className="metric-tile-value">{value}</strong>
      {detail && <p className="metric-tile-detail">{detail}</p>}
      {action && <div className="metric-tile-action">{action}</div>}
    </article>
  );
}

export function KeyValueList({
  items,
  className
}: {
  items: Array<{ label: string; value: ReactNode }>;
  className?: string;
}) {
  return (
    <dl className={joinClassNames("key-value-list", className)}>
      {items.map((item) => (
        <div key={String(item.label)} className="key-value-row">
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function InfoListRow({
  title,
  subtitle,
  tone = "neutral",
  trailing
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  tone?: Tone;
  trailing?: ReactNode;
}) {
  return (
    <div className={joinClassNames("info-list-row", `tone-${tone}`)}>
      <div className="info-list-copy">
        <strong>{title}</strong>
        {subtitle && <span>{subtitle}</span>}
      </div>
      {trailing && <div className="info-list-trailing">{trailing}</div>}
    </div>
  );
}

export function EmptyState({
  title,
  detail,
  action
}: {
  title: string;
  detail: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state-block">
      <strong>{title}</strong>
      <p>{detail}</p>
      {action && <div className="empty-state-action">{action}</div>}
    </div>
  );
}

export function StageActionButton({
  eyebrow,
  label,
  detail,
  onClick,
  disabled = false,
  tone = "neutral"
}: {
  eyebrow: string;
  label: string;
  detail: string;
  onClick: () => void;
  disabled?: boolean;
  tone?: Tone;
}) {
  return (
    <button
      type="button"
      className={joinClassNames("stage-action-button", `tone-${tone}`)}
      onClick={onClick}
      disabled={disabled}
    >
      <span>{eyebrow}</span>
      <strong>{label}</strong>
      <small>{detail}</small>
    </button>
  );
}
