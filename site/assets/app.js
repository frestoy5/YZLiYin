/* 音之理音 · 落地页交互
   纯原生 JS，无依赖；所有增强均为可选——禁用 JS 时页面仍完整可读 */
(function () {
  'use strict';

  var DICT = window.YZI18N || {};
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  // 向 CSS 证明“脚本确实跑起来了”，只有此时才禁用样式表里的静态兜底
  document.documentElement.classList.add('boot-ok');

  /* ---------------------------------------------------------
     0. 诊断：?diag 把横向溢出的元素写进 DOM（排查窄屏布局用）
     --------------------------------------------------------- */
  if (/[?&]diag\b/.test(location.search)) {
    window.addEventListener('load', function () {
      setTimeout(function () {
        var root = document.documentElement;
        var vw = root.clientWidth, bad = [];
        var all = document.querySelectorAll('body *');
        for (var i = 0; i < all.length && bad.length < 30; i++) {
          var el = all[i], r = el.getBoundingClientRect();
          if (r.right > vw + 1) {
            var cn = typeof el.className === 'string' ? el.className
                   : (el.className && el.className.baseVal) || '';
            bad.push(el.tagName + (cn ? '.' + cn.split(' ')[0] : '') + '@' + Math.round(r.right));
          }
        }
        var box = document.createElement('pre');
        box.id = 'diag';
        box.textContent = 'scrollWidth=' + root.scrollWidth + ' clientWidth=' + vw +
          ' offenders=' + (bad.join(' , ') || 'none') + ' || ' + (function () {
            var cta = document.querySelector('.hero .cta');
            if (!cta) return 'no cta';
            var w = Math.round(cta.getBoundingClientRect().width);
            var sum = 0, parts = [];
            for (var j = 0; j < cta.children.length; j++) {
              var bw = Math.round(cta.children[j].getBoundingClientRect().width);
              parts.push(bw); sum += bw;
            }
            return 'ctaWidth=' + w + ' btns=' + parts.join('+') + ' total=' + (sum + (parts.length - 1) * 12);
          })();
        document.body.appendChild(box);
      }, 300);
    });
  }

  /* ---------------------------------------------------------
     1. 启动加载遮罩（Google 式）：等资源就绪后淡出，带兜底超时
     --------------------------------------------------------- */
  var boot = $('#boot');
  var booted = false;
  // 调试参数：?instant 跳过启动屏与进场动画（供无头截图/自动化测试，正常访问不受影响）
  var INSTANT = /[?&]instant\b/.test(location.search);
  if (INSTANT) document.documentElement.classList.add('no-anim');
  document.body.classList.add('is-booting');

  function finishBoot() {
    if (booted) return;
    booted = true;
    var elapsed = Date.now() - START;
    var wait = INSTANT ? 0 : Math.max(0, 750 - elapsed); // 最短显示，避免闪一下就没了
    setTimeout(function () {
      boot.classList.add('is-done');
      if (INSTANT) boot.style.display = 'none';
      document.body.classList.remove('is-booting');
      revealHero();
    }, wait);
  }

  var START = Date.now();
  if (document.readyState === 'complete') finishBoot();
  else window.addEventListener('load', finishBoot);
  setTimeout(finishBoot, 3500); // 兜底：慢网络/慢虚拟主机也不会卡住

  function revealHero() {
    $$('.hero .reveal').forEach(function (el, i) {
      setTimeout(function () { el.classList.add('is-in'); }, INSTANT ? 0 : i * 90);
    });
  }

  /* ---------------------------------------------------------
     2. 三语切换
     --------------------------------------------------------- */
  var langBtn = $('#langBtn'), langMenu = $('#langMenu');
  var current = 'zh';

  function applyLang(code) {
    if (!DICT || !DICT[code]) code = 'zh';
    var d = DICT && DICT[code];
    if (!d) return; // i18n.js 缺失时保持 HTML 里的默认中文，不抛错
    current = code;

    document.documentElement.lang = d._lang || code;
    document.documentElement.setAttribute('data-lang', code);
    document.title = d._title || document.title;

    $$('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (d[key] != null) el.textContent = d[key];
    });

    $$('.lang__item').forEach(function (b) {
      b.classList.toggle('is-active', b.getAttribute('data-set-lang') === code);
    });

    try { localStorage.setItem('yzliyin-lang', code); } catch (e) {}
  }

  function firstLang() {
    //  ?lang=xx 优先（可用于直接分享某个语言的链接）
    var qs = /[?&]lang=(zh|en|ja)\b/.exec(location.search || '');
    if (qs && DICT[qs[1]]) return qs[1];

    var saved = null;
    try { saved = localStorage.getItem('yzliyin-lang'); } catch (e) {}
    if (saved && DICT[saved]) return saved;

    var nav = (navigator.language || 'zh').toLowerCase();
    if (nav.indexOf('ja') === 0) return 'ja';
    if (nav.indexOf('zh') === 0) return 'zh';
    if (nav.indexOf('en') === 0) return 'en';
    return 'zh';
  }

  function closeMenu() {
    if (!langMenu) return;
    langMenu.hidden = true;
    langBtn.setAttribute('aria-expanded', 'false');
  }

  if (langBtn && langMenu) {
    langBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = langMenu.hidden;
      langMenu.hidden = !open;
      langBtn.setAttribute('aria-expanded', String(open));
    });

    $$('.lang__item', langMenu).forEach(function (b) {
      b.addEventListener('click', function () {
        applyLang(b.getAttribute('data-set-lang'));
        closeMenu();
      });
    });

    document.addEventListener('click', function (e) {
      if (!langMenu.hidden && !langMenu.contains(e.target)) closeMenu();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeMenu();
    });
  }

  applyLang(firstLang());

  /* ---------------------------------------------------------
     3. 滚动进场（IntersectionObserver）
     --------------------------------------------------------- */
  var revealables = $$('.reveal').filter(function (el) { return !el.closest('.hero'); });

  // ?instant：不走观察器，直接给出最终状态（截图/自动化用）
  if (INSTANT) revealables.forEach(function (el) { el.classList.add('is-in'); });

  if ('IntersectionObserver' in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var el = en.target;
        var sibs = Array.prototype.filter.call(
          el.parentNode ? el.parentNode.children : [],
          function (n) { return n.classList && n.classList.contains('reveal'); }
        );
        var idx = Math.max(0, sibs.indexOf(el));
        setTimeout(function () { el.classList.add('is-in'); }, Math.min(idx, 8) * 80);
        io.unobserve(el);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });

    revealables.forEach(function (el) { io.observe(el); });
  } else {
    revealables.forEach(function (el) { el.classList.add('is-in'); });
  }

  /* ---------------------------------------------------------
     4. Material 水波纹
     --------------------------------------------------------- */
  document.addEventListener('pointerdown', function (e) {
    var host = e.target.closest ? e.target.closest('.ripple') : null;
    if (!host) return;
    var r = host.getBoundingClientRect();
    var size = Math.max(r.width, r.height);
    var span = document.createElement('span');
    span.className = 'rp';
    span.style.width = span.style.height = size + 'px';
    span.style.left = (e.clientX - r.left - size / 2) + 'px';
    span.style.top = (e.clientY - r.top - size / 2) + 'px';
    host.appendChild(span);
    setTimeout(function () { span.remove(); }, 620);
  });

  /* ---------------------------------------------------------
     5. 顶栏状态：阴影、阅读进度条、返回顶部
     --------------------------------------------------------- */
  var appbar = $('#appbar'), bar = $('#topbarProgress'), fab = $('#toTop');
  var ticking = false;

  function onScroll() {
    var y = window.pageYOffset || document.documentElement.scrollTop;
    appbar.classList.toggle('is-scrolled', y > 8);

    var h = document.documentElement.scrollHeight - window.innerHeight;
    var p = h > 0 ? Math.min(100, (y / h) * 100) : 0;
    bar.style.width = p + '%';
    bar.style.opacity = p > 0.5 ? '1' : '0';

    if (fab) fab.hidden = y < 600;
    ticking = false;
  }

  window.addEventListener('scroll', function () {
    if (!ticking) { ticking = true; requestAnimationFrame(onScroll); }
  }, { passive: true });
  onScroll();

  if (fab) {
    fab.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  /* ---------------------------------------------------------
     6. 移动端导航抽屉
     --------------------------------------------------------- */
  var navToggle = $('#navToggle'), nav = $('.nav');
  if (navToggle && nav) {
    navToggle.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = nav.classList.toggle('is-open');
      navToggle.setAttribute('aria-expanded', String(open));
    });
    $$('.nav__link', nav).forEach(function (a) {
      a.addEventListener('click', function () {
        nav.classList.remove('is-open');
        navToggle.setAttribute('aria-expanded', 'false');
      });
    });
    document.addEventListener('click', function (e) {
      if (nav.classList.contains('is-open') && !nav.contains(e.target) && e.target !== navToggle) {
        nav.classList.remove('is-open');
        navToggle.setAttribute('aria-expanded', 'false');
      }
    });
  }

  /* ---------------------------------------------------------
     7. 锚点滚动时避开 sticky 顶栏
     --------------------------------------------------------- */
  $$('a[href^="#"]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      var id = a.getAttribute('href');
      if (id === '#' || id.length < 2) return;
      var target = document.querySelector(id);
      if (!target) return;
      e.preventDefault();
      var top = target.getBoundingClientRect().top + window.pageYOffset - 72;
      window.scrollTo({ top: top, behavior: 'smooth' });
    });
  });
})();
