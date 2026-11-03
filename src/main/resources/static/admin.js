for (const form of document.querySelectorAll("form[data-confirm-project]")) {
    form.addEventListener("submit", (event) => {
        if (!window.confirm(form.dataset.confirm)) event.preventDefault();
    });
}
