(() => {
  let pending;
  const buttons = [...document.querySelectorAll('[data-install-app]')];
  const standalone = () => ['standalone', 'fullscreen', 'minimal-ui', 'window-controls-overlay']
    .some(mode => matchMedia(`(display-mode: ${mode})`).matches)
    || navigator.standalone === true
    || document.referrer.startsWith('android-app://');

  async function installed() {
    if (standalone()) return true;
    if (typeof navigator.getInstalledRelatedApps !== 'function') return false;
    try {
      const apps = await navigator.getInstalledRelatedApps();
      return apps.some(app => app.platform === 'webapp');
    } catch {
      return false;
    }
  }

  async function update() {
    const isInstalled = await installed();
    buttons.forEach(button => { button.hidden = isInstalled; });
  }

  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    pending = event;
    update();
  });

  window.addEventListener('appinstalled', () => {
    pending = null;
    buttons.forEach(button => { button.hidden = true; });
  });

  buttons.forEach(button => button.addEventListener('click', async () => {
    if (await installed()) {
      update();
      return;
    }
    if (pending) {
      const event = pending;
      pending = null;
      await event.prompt();
      await event.userChoice;
      update();
      return;
    }
    let note = document.querySelector('.install-instructions');
    if (!note) {
      note = document.createElement('p');
      note.className = 'install-instructions';
      note.setAttribute('role', 'status');
      button.parentElement.append(note);
    }
    note.textContent = /iPhone|iPad|iPod/.test(navigator.userAgent)
      ? 'Om appen redan är installerad, öppna den från hemskärmen. Annars: i Safari, tryck på Dela och välj Lägg till på hemskärmen.'
      : 'Om appen redan är installerad, öppna den från hemskärmen. Annars: i Chrome, öppna menyn ⋮ och välj Lägg till på startskärmen eller Installera app. Använd https://2flygarecalender.vercel.app.';
  }));

  update();
})();
