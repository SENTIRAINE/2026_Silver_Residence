document.addEventListener("DOMContentLoaded", () => {
    const tabs = Array.from(document.querySelectorAll("[data-profile-tab]"));
    const views = Array.from(document.querySelectorAll("[data-profile-view]"));
    const toast = document.querySelector(".profile-toast");
    let toastTimer = null;

    const openTab = (name) => {
        tabs.forEach((tab) => {
            const active = tab.dataset.profileTab === name;
            tab.classList.toggle("is-active", active);
            if (active) tab.setAttribute("aria-current", "page");
            else tab.removeAttribute("aria-current");
        });
        views.forEach((view) => {
            const active = view.dataset.profileView === name;
            view.hidden = !active;
            view.classList.toggle("is-active", active);
        });
        window.scrollTo({ top: 0, behavior: "smooth" });
    };

    tabs.forEach((tab) => tab.addEventListener("click", () => openTab(tab.dataset.profileTab)));
    document.querySelectorAll("[data-open-tab]").forEach((button) => {
        button.addEventListener("click", () => openTab(button.dataset.openTab));
    });

    document.querySelector("[data-static-form]")?.addEventListener("submit", (event) => {
        event.preventDefault();
        if (!toast) return;
        toast.hidden = false;
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(() => {
            toast.hidden = true;
        }, 2200);
    });

    document.querySelector("[data-large-text]")?.addEventListener("change", (event) => {
        document.body.classList.toggle("has-large-text", event.currentTarget.checked);
    });
    document.querySelector("[data-reduced-motion]")?.addEventListener("change", (event) => {
        document.body.classList.toggle("has-reduced-motion", event.currentTarget.checked);
    });
});
