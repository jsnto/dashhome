import HAFullPanel from './HAFullPanel'

/**
 * The "Home" section: the real Home Assistant dashboard embedded in an
 * iframe (HAFullPanel). The board view and its entity polling are gone —
 * this component is just the card shell and header around the embed; the
 * embed bar (note, "Open in new tab", Reload) and its error note live in
 * HAFullPanel.
 */
export default function HAPanel() {
  return (
    <section className="card">
      <div className="card-head">
        <h2>Home</h2>
      </div>
      <HAFullPanel />
    </section>
  )
}
