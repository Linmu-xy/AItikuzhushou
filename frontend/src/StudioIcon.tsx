import type { CSSProperties } from 'react';

type IconName = 'plus' | 'arrow' | 'attach' | 'globe' | 'spark' | 'search' | 'file' | 'close' | 'clock' | 'book' | 'check';

export function StudioIcon({ name, size = 20 }: { name: IconName; size?: number }) {
  const paths = {
    plus: <path d="M12 5v14M5 12h14" />,
    arrow: <><path d="M5 12h14m-6-6 6 6-6 6" /></>,
    attach: <path d="m8 12 5.5-5.5a3 3 0 0 1 4.2 4.2l-7.4 7.4a4.5 4.5 0 0 1-6.4-6.4l8.4-8.4" />,
    globe: <><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a19 19 0 0 1 0 18 19 19 0 0 1 0-18Z" /></>,
    spark: <><path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4 4" /></>,
    file: <><path d="M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8Z" /><path d="M14 3v5h5M9 12h6M9 16h4" /></>,
    close: <path d="m6 6 12 12M6 18 18 6" />,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    book: <><path d="M12 5v15M3 4.5c3-1 6 0 9 1.5 3-1.5 6-2.5 9-1.5v14c-3-1-6 0-9 1.5-3-1.5-6-2.5-9-1.5Z" /></>,
    check: <path d="m5 12 4 4L19 6" />,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

export function StudioMark({ large = false }: { large?: boolean }) {
  return <svg className={large ? 'studio-mark large' : 'studio-mark'} width={large ? 64 : 32} height={large ? 64 : 32} viewBox="0 0 40 40" fill="none" aria-hidden="true">
    <path d="M7 8h12v24H7V8Z" fill="currentColor" opacity=".16" />
    <path d="M21 8h12v24H21V8Z" fill="currentColor" />
    <path d="M11 13h4M11 18h4M25 23h4M25 27h4" stroke="var(--mark-detail, white)" strokeWidth="1.8" strokeLinecap="round" />
    <path d="M7 32h26" stroke="currentColor" strokeWidth="1.5" />
  </svg>;
}

export function KnowledgeGraphic() {
  return <div className="knowledge-graphic" aria-hidden="true">
    {[0, 1, 2].map(index => <div className="knowledge-sheet" key={index} style={{ '--sheet': index } as CSSProperties}>
      <span className="sheet-line" /><span className="sheet-line" /><span className="sheet-line short" />
      {index === 2 && <StudioIcon name="spark" size={25} />}
    </div>)}
  </div>;
}
