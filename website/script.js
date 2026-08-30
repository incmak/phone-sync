const header = document.querySelector('[data-site-header]');
const stage = document.querySelector('[data-hero-stage]');
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

function updateHeader() {
  header?.toggleAttribute('data-scrolled', window.scrollY > 24);
}

function updateStage(event) {
  if (!stage || reduceMotion.matches || window.innerWidth < 760) return;
  const bounds = stage.getBoundingClientRect();
  const x = ((event.clientX - bounds.left) / bounds.width - 0.5) * 2;
  const y = ((event.clientY - bounds.top) / bounds.height - 0.5) * 2;
  stage.style.setProperty('--pointer-x', x.toFixed(3));
  stage.style.setProperty('--pointer-y', y.toFixed(3));
}

window.addEventListener('scroll', updateHeader, { passive: true });
stage?.addEventListener('pointermove', updateStage, { passive: true });
stage?.addEventListener('pointerleave', () => {
  stage.style.setProperty('--pointer-x', '0');
  stage.style.setProperty('--pointer-y', '0');
});

document.querySelectorAll('[data-year]').forEach((node) => {
  node.textContent = String(new Date().getFullYear());
});

updateHeader();
