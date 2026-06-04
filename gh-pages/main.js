/* Virga showcase site — progressive-enhancement JS.
 *
 * Owns: precipitation canvas (#precip), scroll reveal ([data-reveal]),
 * mobile nav (.nav-toggle + #site-nav), optional active-nav highlight.
 *
 * Everything degrades gracefully: missing elements never throw, and when
 * prefers-reduced-motion is set, the canvas stays empty and reveals are
 * left in their default visible state. No libraries, no build step.
 */
(function () {
  "use strict";

  // Mark that JS is running. The mobile nav collapses to a toggle only under
  // `.js`; with JS off, the full nav stays visible and reachable.
  document.documentElement.classList.add("js");

  // Motion gate. Read once, but also re-check live so the experience tracks
  // the OS setting if it changes mid-session.
  var motionQuery =
    typeof window.matchMedia === "function"
      ? window.matchMedia("(prefers-reduced-motion: reduce)")
      : null;

  function motionAllowed() {
    return !(motionQuery && motionQuery.matches);
  }

  /* ------------------------------------------------------------------ *
   * 1. Precipitation — calm, slow, downward streaks that fade as they
   *    fall, in the blue→teal brand palette. Low density. Pauses when
   *    the tab is hidden or the canvas is scrolled off screen. Draws
   *    nothing under prefers-reduced-motion.
   * ------------------------------------------------------------------ */
  function initPrecip() {
    var canvas = document.getElementById("precip");
    if (!canvas || !canvas.getContext) return;

    var ctx = canvas.getContext("2d");
    if (!ctx) return;

    // Brand seeds (BRAND.md §4.1 / DESIGN.md tokens).
    var BLUE = [30, 111, 217]; // #1E6FD9
    var TEAL = [31, 168, 160]; // #1FA8A0

    var dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
    var cssW = 0; // logical (CSS px) width
    var cssH = 0; // logical (CSS px) height
    var drops = [];
    var rafId = null;
    var lastTs = 0;
    var visible = true; // canvas is on screen
    var running = false;

    function lerp(a, b, t) {
      return a + (b - a) * t;
    }

    // A single streak. Tint blends blue→teal down its length; alpha is
    // shaped per-frame so streaks fade out before the bottom edge.
    function makeDrop(seeded) {
      var len = 14 + Math.random() * 30; // streak length, CSS px
      return {
        x: Math.random() * cssW,
        // When seeding the initial field, scatter across the height;
        // otherwise spawn just above the top so motion reads downward.
        y: seeded ? Math.random() * cssH : -len - Math.random() * cssH * 0.3,
        len: len,
        // Slow: ~18–45 CSS px/sec. Atmosphere, not a storm.
        speed: 18 + Math.random() * 27,
        thickness: 0.8 + Math.random() * 1.1,
        tint: Math.random(), // 0 = bluer, 1 = tealer
        peak: 0.14 + Math.random() * 0.16, // max opacity, kept low
      };
    }

    // Target a low, area-scaled count so density feels consistent across
    // viewport sizes but never busy.
    function targetCount() {
      var area = cssW * cssH;
      return Math.max(8, Math.min(46, Math.round(area / 16000)));
    }

    function rebuildDrops() {
      var want = targetCount();
      drops.length = 0;
      for (var i = 0; i < want; i++) drops.push(makeDrop(true));
    }

    function resize() {
      var rect = canvas.getBoundingClientRect();
      var w = Math.max(1, Math.floor(rect.width));
      var h = Math.max(1, Math.floor(rect.height));
      if (w === cssW && h === cssH) return;
      cssW = w;
      cssH = h;
      dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
      canvas.width = Math.floor(cssW * dpr);
      canvas.height = Math.floor(cssH * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      rebuildDrops();
    }

    function clear() {
      ctx.clearRect(0, 0, cssW, cssH);
    }

    function drawDrop(d) {
      // Fade as it falls: full strength in the upper band, easing to 0 by
      // ~78% of the canvas height — rain that evaporates before it lands.
      var progress = d.y / cssH;
      var fade;
      if (progress < 0.08) {
        fade = progress / 0.08; // ease in at the top
      } else if (progress > 0.78) {
        fade = 0;
      } else {
        fade = 1 - (progress - 0.08) / (0.78 - 0.08);
      }
      if (fade <= 0) return;

      var alpha = d.peak * fade;
      var r = Math.round(lerp(BLUE[0], TEAL[0], d.tint));
      var g = Math.round(lerp(BLUE[1], TEAL[1], d.tint));
      var b = Math.round(lerp(BLUE[2], TEAL[2], d.tint));
      var x = d.x;
      var y0 = d.y;
      var y1 = d.y + d.len;

      // Soft vertical streak: solid-ish at the head, transparent at the tail.
      var grad = ctx.createLinearGradient(x, y0, x, y1);
      grad.addColorStop(0, "rgba(" + r + "," + g + "," + b + ",0)");
      grad.addColorStop(
        0.5,
        "rgba(" + r + "," + g + "," + b + "," + alpha + ")",
      );
      grad.addColorStop(1, "rgba(" + r + "," + g + "," + b + ",0)");

      ctx.strokeStyle = grad;
      ctx.lineWidth = d.thickness;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.moveTo(x, y0);
      ctx.lineTo(x, y1);
      ctx.stroke();

      // A faint droplet head for a touch of "rain" texture.
      ctx.fillStyle = "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
      ctx.beginPath();
      ctx.arc(x, y1, d.thickness * 0.9, 0, Math.PI * 2);
      ctx.fill();
    }

    function frame(ts) {
      rafId = null;
      if (!running) return;

      var dt = lastTs ? (ts - lastTs) / 1000 : 0;
      lastTs = ts;
      // Clamp dt so a backgrounded-then-resumed tab doesn't teleport drops.
      if (dt > 0.05) dt = 0.05;

      clear();
      for (var i = 0; i < drops.length; i++) {
        var d = drops[i];
        d.y += d.speed * dt;
        if (d.y - d.len > cssH) {
          // Recycle from the top with fresh randomness.
          drops[i] = makeDrop(false);
        } else {
          drawDrop(d);
        }
      }
      schedule();
    }

    function schedule() {
      if (rafId == null && running) rafId = window.requestAnimationFrame(frame);
    }

    function start() {
      if (running || !motionAllowed() || !visible) return;
      if (cssW === 0 || cssH === 0) resize();
      running = true;
      lastTs = 0;
      schedule();
    }

    function stop() {
      running = false;
      if (rafId != null) {
        window.cancelAnimationFrame(rafId);
        rafId = null;
      }
    }

    // --- Lifecycle wiring -------------------------------------------------

    // Reduced motion: never animate. Leave the canvas blank (pure decoration;
    // the hero is designed to read with an empty canvas).
    if (!motionAllowed()) {
      resize();
      clear();
      return;
    }

    resize();

    // Keep size in sync with the container.
    if (typeof ResizeObserver === "function") {
      var ro = new ResizeObserver(function () {
        resize();
      });
      ro.observe(canvas);
    } else if (window.addEventListener) {
      window.addEventListener("resize", resize, { passive: true });
    }

    // Pause when the canvas isn't on screen.
    if (typeof IntersectionObserver === "function") {
      var io = new IntersectionObserver(
        function (entries) {
          for (var i = 0; i < entries.length; i++) {
            visible = entries[i].isIntersecting;
          }
          if (visible) start();
          else stop();
        },
        { threshold: 0 },
      );
      io.observe(canvas);
    } else {
      visible = true;
    }

    // Pause when the tab is hidden.
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) stop();
      else if (visible) start();
    });

    // React if the OS reduced-motion preference flips on/off live.
    if (motionQuery) {
      var onMotionChange = function () {
        if (motionAllowed()) {
          start();
        } else {
          stop();
          clear();
        }
      };
      if (typeof motionQuery.addEventListener === "function") {
        motionQuery.addEventListener("change", onMotionChange);
      } else if (typeof motionQuery.addListener === "function") {
        motionQuery.addListener(onMotionChange); // older Safari
      }
    }

    start();
  }

  /* ------------------------------------------------------------------ *
   * 2. Scroll reveal — fade/slide-in [data-reveal] elements once they
   *    enter the viewport. Only ever HIDES content when we can guarantee
   *    we'll reveal it (motion allowed + IntersectionObserver present).
   *    Otherwise elements stay in their default visible state.
   * ------------------------------------------------------------------ */
  function initReveal() {
    var els = document.querySelectorAll("[data-reveal]");
    if (!els.length) return;

    // If we can't observe, or motion is off, do nothing — CSS default is
    // visible, so content is never trapped hidden.
    if (!motionAllowed() || typeof IntersectionObserver !== "function") return;

    var i;
    // Apply the pre-animation state only now that we know we can reveal.
    for (i = 0; i < els.length; i++) {
      els[i].classList.add("is-pre-reveal");
    }

    var io = new IntersectionObserver(
      function (entries, observer) {
        for (var j = 0; j < entries.length; j++) {
          var entry = entries[j];
          if (entry.isIntersecting) {
            entry.target.classList.add("is-revealed");
            entry.target.classList.remove("is-pre-reveal");
            observer.unobserve(entry.target); // one-shot
          }
        }
      },
      { threshold: 0.12, rootMargin: "0px 0px -8% 0px" },
    );

    for (i = 0; i < els.length; i++) io.observe(els[i]);
  }

  /* ------------------------------------------------------------------ *
   * 3. Mobile nav toggle — open/close #site-nav, keep aria-expanded in
   *    sync, close on link click and on Escape.
   * ------------------------------------------------------------------ */
  function initNav() {
    var toggle = document.querySelector(".nav-toggle");
    var nav = document.getElementById("site-nav");
    if (!toggle || !nav) return;

    function isOpen() {
      return toggle.getAttribute("aria-expanded") === "true";
    }

    function setOpen(open) {
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      nav.classList.toggle("is-open", open);
    }

    setOpen(false);

    toggle.addEventListener("click", function () {
      setOpen(!isOpen());
    });

    // Close when a nav link is followed.
    nav.addEventListener("click", function (e) {
      var t = e.target;
      if (t && t.closest && t.closest("a")) setOpen(false);
    });

    // Close on Escape, returning focus to the toggle.
    document.addEventListener("keydown", function (e) {
      if ((e.key === "Escape" || e.key === "Esc") && isOpen()) {
        setOpen(false);
        if (typeof toggle.focus === "function") toggle.focus();
      }
    });
  }

  /* ------------------------------------------------------------------ *
   * 4. Active-section nav highlight (optional polish). Marks the nav
   *    link for the section currently in view with .is-current.
   * ------------------------------------------------------------------ */
  function initActiveNav() {
    var nav = document.getElementById("site-nav");
    if (!nav || typeof IntersectionObserver !== "function") return;

    var links = nav.querySelectorAll('a[href^="#"]');
    if (!links.length) return;

    // Map section id -> link, and collect the sections we can observe.
    var linkFor = {};
    var sections = [];
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute("href") || "";
      var id = href.charAt(0) === "#" ? href.slice(1) : "";
      if (!id) continue;
      var section = document.getElementById(id);
      if (!section) continue;
      linkFor[id] = links[i];
      sections.push(section);
    }
    if (!sections.length) return;

    function setCurrent(id) {
      for (var k = 0; k < links.length; k++) {
        links[k].classList.remove("is-current");
        links[k].removeAttribute("aria-current");
      }
      var link = linkFor[id];
      if (link) {
        link.classList.add("is-current");
        link.setAttribute("aria-current", "true");
      }
    }

    var io = new IntersectionObserver(
      function (entries) {
        // Pick the most-visible intersecting section.
        var best = null;
        for (var j = 0; j < entries.length; j++) {
          var e = entries[j];
          if (!e.isIntersecting) continue;
          if (!best || e.intersectionRatio > best.intersectionRatio) best = e;
        }
        if (best && best.target.id) setCurrent(best.target.id);
      },
      { threshold: [0.25, 0.5, 0.75], rootMargin: "-45% 0px -45% 0px" },
    );

    for (var s = 0; s < sections.length; s++) io.observe(sections[s]);
  }

  /* ------------------------------------------------------------------ *
   * Boot. Script is loaded with `defer`, so the DOM is parsed, but guard
   * for the readyState edge case regardless.
   * ------------------------------------------------------------------ */
  function init() {
    try {
      initPrecip();
    } catch (e) {
      /* decoration only — never block the page */
    }
    try {
      initReveal();
    } catch (e) {}
    try {
      initNav();
    } catch (e) {}
    try {
      initActiveNav();
    } catch (e) {}
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
