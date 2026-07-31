interface PlaceholderMetric {
  label: string
  value: string
}

interface AdminPlaceholderPageProps {
  eyebrow: string
  title: string
  status: string
  summary: string
  metrics: PlaceholderMetric[]
  backlog: string[]
}

type AdminPlaceholderKey = 'permissions' | 'staticData' | 'execution' | 'portfolio'

const adminPlaceholderPages: Record<AdminPlaceholderKey, AdminPlaceholderPageProps> = {
  permissions: {
    eyebrow: 'Identity controls',
    title: 'Permission Administration',
    status: 'Planned',
    summary: 'Role and trading-permission controls are grouped here so user access can move beyond account-level edits.',
    metrics: [
      { label: 'Baseline roles', value: '2' },
      { label: 'Trading flag', value: 'can_trade' },
      { label: 'Admin route', value: 'Protected' },
    ],
    backlog: ['Desk-level permission policies', 'Permission change audit trail', 'Role assignment review queue'],
  },
  staticData: {
    eyebrow: 'Reference data',
    title: 'Static Data Administration',
    status: 'Planned',
    summary: 'Static market data administration belongs in this section for listings, exchanges, venues, and currency metadata.',
    metrics: [
      { label: 'Instruments', value: 'Listings' },
      { label: 'Venues', value: 'MIC' },
      { label: 'Currency', value: 'ISO' },
    ],
    backlog: ['Instrument search and edits', 'Exchange and venue maintenance', 'Bulk upload validation'],
  },
  execution: {
    eyebrow: 'Execution control',
    title: 'Execution Strategy Monitoring',
    status: 'Planned',
    summary: 'Execution monitoring is reserved for strategy health, child-order activity, and routing diagnostics.',
    metrics: [
      { label: 'Strategies', value: 'DMA / SMART / VWAP' },
      { label: 'Signals', value: 'Health' },
      { label: 'Latency', value: 'Monitor' },
    ],
    backlog: ['Strategy runtime status', 'Child-order drilldown', 'Routing failure diagnostics'],
  },
  portfolio: {
    eyebrow: 'Risk state',
    title: 'Portfolio State',
    status: 'Planned',
    summary: 'Portfolio administration is reserved for positions, cash, exposure, and reconciliation state across desks.',
    metrics: [
      { label: 'Positions', value: 'Desk' },
      { label: 'Cash', value: 'Currency' },
      { label: 'Exposure', value: 'Risk' },
    ],
    backlog: ['Desk position snapshots', 'Cash balance review', 'Portfolio reconciliation alerts'],
  },
}

export function AdminPlaceholderRoute({ page }: { page: AdminPlaceholderKey }) {
  return <AdminPlaceholderPage {...adminPlaceholderPages[page]} />
}

function AdminPlaceholderPage({
  eyebrow,
  title,
  status,
  summary,
  metrics,
  backlog,
}: AdminPlaceholderPageProps) {
  return (
    <main className="admin-users-main admin-placeholder-main">
      <section className="admin-placeholder-summary">
        <div className="admin-panel-heading">
          <div><span>{eyebrow}</span><h1>{title}</h1></div>
          <strong className="admin-pill">{status}</strong>
        </div>
        <p>{summary}</p>
      </section>

      <section className="admin-placeholder-grid" aria-label={`${title} state`}>
        {metrics.map((metric) => (
          <div className="admin-placeholder-card" key={metric.label}>
            <span>{metric.label}</span>
            <strong>{metric.value}</strong>
          </div>
        ))}
      </section>

      <section className="admin-placeholder-list">
        <div className="admin-panel-heading">
          <div><span>Next controls</span><h2>Queued controls</h2></div>
        </div>
        <ul>
          {backlog.map((item) => <li key={item}>{item}</li>)}
        </ul>
      </section>
    </main>
  )
}
