const containers = new Map()

function ensureContainer(type) {
  let container = containers.get(type)
  if (!container || !document.body.contains(container)) {
    container = document.createElement('div')
    container.className = `app-message-container app-message-${type}`
    document.body.appendChild(container)
    containers.set(type, container)
  }
  return container
}

const ICONS = {
  success: 'M9 16.2l-3.5-3.5L4 14.2 9 19.2 20 8.2l-1.4-1.4z',
  error: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 13.6L15.6 17 12 13.4 8.4 17 7 15.6 10.6 12 7 8.4 8.4 7 12 10.6 15.6 7 17 8.4 13.4 12z',
  warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
}

function show(message, type = 'info', duration = 3000) {
  const container = ensureContainer(type)
  const el = document.createElement('div')
  el.className = 'app-message-item'
  el.innerHTML = `
    <svg viewBox="0 0 24 24" class="app-message-icon"><path d="${ICONS[type] || ICONS.info}"/></svg>
    <span></span>
  `
  el.querySelector('span').textContent = message
  container.appendChild(el)
  requestAnimationFrame(() => el.classList.add('show'))
  setTimeout(() => {
    el.classList.remove('show')
    setTimeout(() => el.remove(), 300)
  }, duration)
}

export const ElMessage = {
  success: (msg) => show(msg, 'success'),
  error: (msg) => show(msg, 'error'),
  warning: (msg) => show(msg, 'warning'),
  info: (msg) => show(msg, 'info'),
}
