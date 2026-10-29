/** Profile picture, or the initial of the last name (Vietnamese names end with the given name). */
export function Avatar({ name, url, size = 34 }: { name: string; url: string | null; size?: number }) {
  const initial = name.trim().split(/\s+/).pop()?.[0]?.toUpperCase() ?? '?'
  return url
    ? <img className="avatar" src={url} alt="" width={size} height={size} style={{ width: size, height: size }} />
    : <span className="avatar avatar-initial" aria-hidden style={{ width: size, height: size, fontSize: size * 0.45 }}>{initial}</span>
}
