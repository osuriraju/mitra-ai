'use client';
/* =====================================================================
   COCKPIT SHELL — single-row top nav (priority+ tabs → "More ▾" menu,
   never scrolls) on tablet/desktop; top bar + bottom tab bar + sections
   sheet on mobile. Ported from the Noir · Cockpit prototype.
   ===================================================================== */
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { NAV, href, type NavKey, type ScreenId } from '@/lib/routes';
import { Avatar, Btn, Icon } from '@/components/ui';
import { ToastHost } from '@/components/ui/controls';
import { useStore } from '@/store';
import { openToday } from '@/store/selectors';

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');

export type TopAction = { ic?: string; label?: string; kind?: 'primary' | 'outline' | 'ghost' | 'soft' | 'danger'; goto?: ScreenId; title?: string; onClick?: () => void; el?: ReactNode };
export type Top = { title?: string; sub?: string; back?: ScreenId; eyebrow?: string; actions?: TopAction[]; noHead?: boolean };

type MenuId = 'sections' | 'user' | 'sheet' | null;

/* ---------- Top navigation ---------- */
function useNavCounts() {
  const tasks = useStore((s) => s.tasks); const notifs = useStore((s) => s.notifications);
  return { NAV_BADGE: { tasks: openToday(tasks).length } as Record<string, number>, UNREAD: notifs.filter((n) => !n.read).length };
}
/** Signed-in user for the avatar + menu (falls back gracefully while /auth/me is in flight). */
function useMe() {
  const user = useStore((s) => s.user);
  const full = user?.name || '…'; const initials = user ? user.name.split(' ').map((x) => x[0]).join('').slice(0, 2).toUpperCase() : '·';
  return { full, email: user?.email || '', initials };
}
function SignOutItem() {
  const router = useRouter(); const logout = useStore((s) => s.logout); const toast = useStore((s) => s.toast);
  return <a className="mi danger" role="button" onClick={async () => { await logout(); toast('Signed out'); router.replace(href('login')); }}><Icon name="logout" />Sign out</a>;
}
function TopNav({ active, openMenu, setMenu }: { active?: NavKey; openMenu: MenuId; setMenu: (m: MenuId) => void }) {
  const { NAV_BADGE, UNREAD } = useNavCounts(); const me = useMe();
  const strip = useRef<HTMLElement>(null);
  const [hidden, setHidden] = useState<string[]>([]);
  const [moreLeft, setMoreLeft] = useState(0);

  // Priority+ navigation: hide trailing tabs that don't fit and list them under "More ▾".
  useLayoutEffect(() => {
    const el = strip.current; if (!el) return;
    const fit = () => {
      const tabs = [...el.querySelectorAll<HTMLElement>('.tab')]; const more = el.querySelector<HTMLElement>('.more-tab');
      if (!more) return;
      tabs.forEach((t) => t.classList.remove('ov')); more.classList.add('hide');
      if (!el.clientWidth) { setHidden([]); return; } // mobile: strip hidden, bottom bar takes over
      const gap = 2, avail = el.clientWidth; const w = tabs.map((t) => t.offsetWidth + gap);
      if (w.reduce((a, b) => a + b, 0) <= avail) { setHidden([]); return; }
      more.classList.remove('hide');
      const activeIdx = tabs.findIndex((t) => t.classList.contains('on'));
      let used = more.offsetWidth + gap + (activeIdx >= 0 ? w[activeIdx] : 0); let overflowed = false; const hid: string[] = [];
      tabs.forEach((t, i) => { if (i === activeIdx) return; if (!overflowed && used + w[i] <= avail) used += w[i]; else { overflowed = true; t.classList.add('ov'); hid.push(t.dataset.key!); } });
      setHidden(hid); setMoreLeft(more.offsetLeft);
    };
    fit();
    const ro = new ResizeObserver(fit); ro.observe(el);
    document.fonts?.ready.then(fit);
    return () => ro.disconnect();
  }, [active, NAV_BADGE.tasks]);

  const hiddenItems = NAV.filter((n) => hidden.includes(n.key));
  return (
    <div className="topnav">
      <div className="r1">
        <Link href={href('today')} className="logo"><div className="mark">M</div><span>Mitra AI</span></Link>
        <nav className="tabs-strip" aria-label="Sections" ref={strip}>
          {NAV.map((n) => (
            <Link key={n.key} href={href(n.goto)} className={cx('tab', n.key === active && 'on')} data-key={n.key}>
              <Icon name={n.ic} /><span>{n.l}</span>{NAV_BADGE[n.key] ? <span className="nb">{NAV_BADGE[n.key]}</span> : null}
            </Link>
          ))}
          <button type="button" className="more-tab hide" aria-expanded={openMenu === 'sections'} onClick={() => setMenu(openMenu === 'sections' ? null : 'sections')}>
            <Icon name="grid" /><span>More</span>
            <svg className="chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round"><path d="M6 9l6 6 6-6" /></svg>
          </button>
        </nav>
        <div className="right">
          <Link href={href('command')} className="cmd"><Icon name="search" /><span>Search or ask Mitra…</span><span className="kb">⌘K</span></Link>
          <Btn ic="search" kind="ghost" cls="sm search-btn" goto="command" title="Search or ask Mitra (⌘K)" />
          <Btn label="Quick add" ic="plus" kind="primary" cls="sm qa-btn" goto="quickadd" title="Quick add (N)" />
          <Link href={href('notifications')} className="btn ghost sm icon bell" title="Notifications"><Icon name="bell" />{UNREAD ? <span className="dot" /> : null}</Link>
          <div className="avatar sm" role="button" tabIndex={0} aria-expanded={openMenu === 'user'} title={me.full} onClick={() => setMenu(openMenu === 'user' ? null : 'user')}>{me.initials}</div>
        </div>
      </div>

      <div className={cx('menu sections', openMenu === 'sections' && 'open')} style={{ left: moreLeft }}>
        <div className="ml">More sections</div>
        <div className="mi-list">
          {hiddenItems.map((n) => <Link key={n.key} href={href(n.goto)} className={cx('mi', n.key === active && 'on')}><Icon name={n.ic} /><span>{n.l}</span>{NAV_BADGE[n.key] ? <span className="nb">{NAV_BADGE[n.key]}</span> : null}</Link>)}
        </div>
        <div className="divider" />
        <Link href={href('settings')} className={cx('mi', active === 'settings' && 'on')}><Icon name="settings" />Settings</Link>
      </div>

      <div className={cx('menu', openMenu === 'user' && 'open')}>
        <div className="mh"><Avatar i={me.initials} /><div><div className="sm b">{me.full}</div><div className="xs muted">{me.email}</div></div></div>
        <div className="divider" />
        <Link href={href('profile')} className="mi"><Icon name="user" />Profile</Link>
        <Link href={href('settings')} className={cx('mi', active === 'settings' && 'on')}><Icon name="settings" />Settings</Link>
        <Link href={href('command')} className="mi"><Icon name="command" />Command palette<span className="kb">⌘K</span></Link>
        <div className="divider" />
        <SignOutItem />
      </div>
    </div>
  );
}

/* ---------- Mobile: bottom tab bar + "More" sheet listing every section ---------- */
function BottomNav({ active, openMenu, setMenu }: { active?: NavKey; openMenu: MenuId; setMenu: (m: MenuId) => void }) {
  const { NAV_BADGE, UNREAD } = useNavCounts();
  const moreOn = !['today', 'tasks', 'money'].includes(active || '');
  const item = (key: NavKey) => { const n = NAV.find((x) => x.key === key)!; return <Link key={key} href={href(n.goto)} className={active === key ? 'on' : ''}><Icon name={n.ic} /><span>{n.l}</span>{NAV_BADGE[key] ? <span className="nb">{NAV_BADGE[key]}</span> : null}</Link>; };
  return (
    <>
      <nav className="bottomnav">
        {item('today')}{item('tasks')}
        <div className="qa"><Link href={href('quickadd')} className="qa-link" aria-label="Quick add"><button type="button" tabIndex={-1}><Icon name="plus" /></button></Link></div>
        {item('money')}
        <a className={moreOn ? 'on' : ''} role="button" aria-expanded={openMenu === 'sheet'} onClick={() => setMenu(openMenu === 'sheet' ? null : 'sheet')}><Icon name="grid" /><span>More</span></a>
      </nav>
      <div className={cx('sheet-menu', openMenu === 'sheet' && 'open')}>
        <div className="ml">All sections</div>
        <div className="sg">{NAV.map((n) => <Link key={n.key} href={href(n.goto)} className={n.key === active ? 'on' : ''}><Icon name={n.ic} />{n.l}</Link>)}</div>
        <div className="sr">
          <Link href={href('notifications')}><Icon name="bell" />Notifications{UNREAD ? <span className="nb" style={{ display: 'inline', marginLeft: 'auto', color: 'var(--muted)', fontSize: 11 }}>{UNREAD}</span> : null}</Link>
          <Link href={href('settings')} className={active === 'settings' ? 'on' : ''}><Icon name="settings" />Settings</Link>
          <Link href={href('profile')}><Icon name="user" />Profile</Link>
        </div>
      </div>
    </>
  );
}

/* ---------- Page header (always shown in Cockpit) ---------- */
function PageHeader({ top }: { top: Top }) {
  if (top.noHead) return null;
  return (
    <div>
      <div className="row between top">
        <div className="row" style={{ gap: 8 }}>
          {top.back && <Btn ic="arrow-left" kind="ghost" goto={top.back} cls="sm" />}
          <div>
            {top.eyebrow && <div className="eyebrow">{top.eyebrow}</div>}
            <h1 style={{ fontSize: 24 }}>{top.title || ''}</h1>
            {top.sub && <div className="muted sm" style={{ marginTop: 2 }}>{top.sub}</div>}
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          {(top.actions || []).map((a, i) => a.el ? <span key={i}>{a.el}</span> : <Btn key={i} ic={a.ic} label={a.label || ''} kind={a.kind || 'outline'} goto={a.goto} title={a.title} onClick={a.onClick} />)}
        </div>
      </div>
    </div>
  );
}

/* ---------- Screen: shell + content + optional overlay ---------- */
export function Screen({ nav, top, narrow = false, center = false, children, overlay, toast }: { nav?: NavKey; top?: Top; narrow?: boolean; center?: boolean; children: ReactNode; overlay?: ReactNode; toast?: ReactNode }) {
  const [menu, setMenu] = useState<MenuId>(null);
  const pathname = usePathname();
  useEffect(() => { setMenu(null); }, [pathname]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenu(null); };
    document.addEventListener('keydown', onKey); return () => document.removeEventListener('keydown', onKey);
  }, []);
  return (
    <div className="app">
      <TopNav active={nav} openMenu={menu} setMenu={setMenu} />
      <div className={cx('content', center && 'center')}>
        <div className={cx('wrap', narrow && 'narrow')}>
          {top && <PageHeader top={top} />}
          {children}
        </div>
      </div>
      <BottomNav active={nav} openMenu={menu} setMenu={setMenu} />
      <div className={cx('menu-scrim', menu && 'open')} onClick={() => setMenu(null)} />
      <ToastHost />
      {toast}
      {overlay}
    </div>
  );
}
