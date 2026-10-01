/**
 * The "Home" section's embed: the real Home Assistant dashboard in an
 * iframe, pointing at the viewer's browser's own HA origin. HAPanel owns
 * the card shell, the "Home" title and the embed actions ("Open in new
 * tab", Reload) on its right, and the URL (fetched from /api/ha/embed-url,
 * assembled server-side); this component is just the frame, which fills the
 * card body.
 *
 * A cross-origin frame can fail in ways the page cannot see — the browser
 * renders its own error page (e.g. "10.10.20.243 refused the connection")
 * inside the iframe and fires no error event — so the header's Open-in-new-
 * tab action (same URL) is the way out in that case.
 */
export default function HAFullPanel({ url }: { url: string }) {
  return (
    <iframe
      className="ha-iframe"
      src={url}
      title="Home Assistant dashboard"
      loading="lazy"
      referrerPolicy="no-referrer"
    />
  )
}
