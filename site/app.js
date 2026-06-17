// cc-pocket — marketing site interactions

(function(){
  const root = document.documentElement;

  // ── brand mark + glyph injection (so HTML stays clean) ──
  const MARK = '<svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true">' +
    '<path d="M3 5l4 4-4 4" stroke="#D97757" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>' +
    '<rect x="11" y="4.5" width="3.4" height="13" rx="1.7" fill="#D97757"/></svg>';
  const GH = '<svg width="17" height="17" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">' +
    '<path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>';
  const STAR = '<svg width="13" height="13" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 1.5l1.9 4 4.3.5-3.2 2.9.9 4.3L8 11.9 4.1 13.2l.9-4.3L1.8 6l4.3-.5z"/></svg>';

  document.querySelectorAll('.brand-mark').forEach(e => e.innerHTML = MARK);
  document.querySelectorAll('.gh-glyph').forEach(e => e.innerHTML = GH);
  document.querySelectorAll('.star-ico').forEach(e => e.innerHTML = STAR);

  // ── live GitHub star count (never a fabricated number) ──
  // Pills ship hidden; we reveal them only once we have a real count from the API.
  const starPills = [...document.querySelectorAll('.star-pill')];
  fetch('https://api.github.com/repos/heypandax/cc-pocket')
    .then(r => r.ok ? r.json() : null)
    .then(d => {
      const n = d && d.stargazers_count;
      if (!n) return;                       // unknown or zero → leave the pill hidden
      const txt = n >= 1000 ? (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k' : String(n);
      document.querySelectorAll('.star-n').forEach(e => e.textContent = txt);
      starPills.forEach(p => { p.style.display = ''; });
    })
    .catch(() => {});

  // ── theme ──
  const SUN = '<svg width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><circle cx="10" cy="10" r="3.4"/><path d="M10 1.6v2M10 16.4v2M3.5 3.5l1.4 1.4M15.1 15.1l1.4 1.4M1.6 10h2M16.4 10h2M3.5 16.5l1.4-1.4M15.1 4.9l1.4-1.4"/></svg>';
  const MOON = '<svg width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M16.5 11.2A6.5 6.5 0 018.8 3.5a6.5 6.5 0 107.7 7.7z"/></svg>';
  const themeBtn = document.getElementById('theme-btn');
  function setTheme(t){
    root.setAttribute('data-theme', t);
    themeBtn.innerHTML = t === 'dark' ? MOON : SUN;
    try { localStorage.setItem('ccp-theme', t); } catch(e){}
  }
  let savedTheme = 'dark';
  try { savedTheme = localStorage.getItem('ccp-theme') || 'dark'; } catch(e){}
  setTheme(savedTheme);
  themeBtn.addEventListener('click', () => setTheme(root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark'));

  // ── language ──
  function setLang(l){
    root.setAttribute('data-lang', l);
    root.setAttribute('lang', l === 'zh' ? 'zh-CN' : 'en');
    document.querySelectorAll('[data-setlang]').forEach(b => b.classList.toggle('on', b.dataset.setlang === l));
    try { localStorage.setItem('ccp-lang', l); } catch(e){}
  }
  let savedLang = 'en';
  try { savedLang = localStorage.getItem('ccp-lang') || 'en'; } catch(e){}
  setLang(savedLang);
  document.querySelectorAll('[data-setlang]').forEach(b => b.addEventListener('click', () => setLang(b.dataset.setlang)));

  // ── nav condense on scroll ──
  const nav = document.getElementById('nav');
  const onScroll = () => { nav.classList.toggle('scrolled', window.scrollY > 24); };
  onScroll();
  window.addEventListener('scroll', onScroll, { passive:true });

  // ── mobile menu ──
  const burger = document.getElementById('hamburger');
  const menu = document.getElementById('mobile-menu');
  const BARS = '<svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M2.5 5h13M2.5 9h13M2.5 13h13"/></svg>';
  const X = '<svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M4 4l10 10M14 4L4 14"/></svg>';
  burger.innerHTML = BARS;
  burger.addEventListener('click', () => {
    const open = menu.classList.toggle('open');
    burger.innerHTML = open ? X : BARS;
  });
  menu.querySelectorAll('a').forEach(a => a.addEventListener('click', () => { menu.classList.remove('open'); burger.innerHTML = BARS; }));

  // ── active-section nav indicator ──
  const sections = ['features','how','security','start'].map(id => document.getElementById(id)).filter(Boolean);
  const navLinks = [...document.querySelectorAll('.nav-links a')];
  const spy = new IntersectionObserver((entries) => {
    entries.forEach(e => {
      if (e.isIntersecting){
        const id = e.target.id;
        navLinks.forEach(a => a.classList.toggle('active', a.dataset.sec === id));
      }
    });
  }, { rootMargin: '-45% 0px -50% 0px' });
  sections.forEach(s => spy.observe(s));

  // ── copy buttons ──
  const CHECK = '<svg width="15" height="15" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 9.5l3.5 3.5 7.5-8"/></svg>';
  document.querySelectorAll('.copy').forEach(btn => {
    const original = btn.innerHTML;
    btn.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(btn.dataset.copy); } catch(e){}
      btn.classList.add('done');
      btn.innerHTML = CHECK;
      setTimeout(() => { btn.classList.remove('done'); btn.innerHTML = original; }, 1600);
    });
  });

  // ── reveal on scroll ──
  // Real browsers: IntersectionObserver adds .in → CSS fades it up.
  // Some embedded webviews freeze CSS transitions at frame 0 (the 0→1 opacity
  // fade never runs) — we detect that and force every reveal visible inline so
  // content is NEVER trapped at opacity:0. The designed fade still ships for
  // real users.
  const revealEls = [...document.querySelectorAll('.reveal')];
  let io = null;
  if ('IntersectionObserver' in window){
    io = new IntersectionObserver((entries) => {
      entries.forEach(e => { if (e.isIntersecting){ e.target.classList.add('in'); io.unobserve(e.target); } });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.04 });
    revealEls.forEach(el => io.observe(el));
  } else {
    revealEls.forEach(el => el.classList.add('in'));
  }
  function forceVisible(){
    revealEls.forEach(el => { el.style.transition = 'none'; el.style.opacity = '1'; el.style.transform = 'none'; });
  }
  // probe: does a CSS transition actually progress here?
  const probe = document.createElement('div');
  probe.style.cssText = 'position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:0;transition:opacity .05s linear;pointer-events:none;';
  document.body.appendChild(probe);
  requestAnimationFrame(() => {
    probe.style.opacity = '1';
    setTimeout(() => {
      const frozen = parseFloat(getComputedStyle(probe).opacity) < 0.5;
      probe.remove();
      if (frozen) forceVisible();
    }, 140);
  });
  // ultimate safety net regardless of probe outcome
  setTimeout(() => { revealEls.forEach(el => { if (parseFloat(getComputedStyle(el).opacity) < 0.5) { el.style.transition='none'; el.style.opacity='1'; el.style.transform='none'; } }); }, 2400);
})();
