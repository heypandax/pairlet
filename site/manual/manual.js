(function () {
  const root = document.documentElement;
  const locale = document.body.dataset.locale || "en";

  let savedTheme = "dark";
  try {
    savedTheme = localStorage.getItem("ccp-theme") || "dark";
  } catch (_) {}
  root.setAttribute("data-theme", savedTheme);

  const theme = document.querySelector(".theme-toggle");
  if (theme) {
    theme.addEventListener("click", function () {
      const next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      try {
        localStorage.setItem("ccp-theme", next);
      } catch (_) {}
    });
  }

  async function copyText(value, button, success) {
    try {
      await navigator.clipboard.writeText(value);
    } catch (_) {
      const input = document.createElement("textarea");
      input.value = value;
      input.setAttribute("readonly", "");
      input.style.position = "fixed";
      input.style.opacity = "0";
      document.body.appendChild(input);
      input.select();
      document.execCommand("copy");
      input.remove();
    }
    const original = button.textContent;
    button.textContent = success;
    button.disabled = true;
    setTimeout(function () {
      button.textContent = original;
      button.disabled = false;
    }, 2200);
  }

  document.querySelectorAll("[data-copy-url]:not([data-copy-ai])").forEach(function (button) {
    button.addEventListener("click", function () {
      copyText(
        button.dataset.copyUrl || location.href,
        button,
        locale === "zh" ? "链接已复制" : "Link copied"
      );
    });
  });

  document.querySelectorAll("[data-copy-ai]").forEach(function (button) {
    button.addEventListener("click", function () {
      const url = button.dataset.copyUrl || location.href;
      const prompt =
        button.dataset.copyPrompt ||
        (locale === "zh"
          ? "打开这份公开的 CC Pocket 用户手册，并依据其中已核验的步骤回答我的问题。"
          : "Open this public CC Pocket manual and answer my question using its verified steps.");
      copyText(url + "\n" + prompt, button, locale === "zh" ? "已复制给 AI" : "Copied for AI");
    });
  });

  document.querySelectorAll("[data-copy-code]").forEach(function (button) {
    button.addEventListener("click", function () {
      copyText(button.dataset.copyCode, button, locale === "zh" ? "已复制" : "Copied");
    });
  });

  const searchInput = document.getElementById("manual-search-input");
  const searchResults = document.getElementById("manual-search-results");
  const searchDataNode = document.getElementById("manual-search-index");
  if (!searchInput || !searchResults || !searchDataNode) return;

  let articles = [];
  try {
    articles = JSON.parse(searchDataNode.textContent);
  } catch (_) {}
  let selected = 0;
  let shown = [];

  function normalize(value) {
    return value.toLocaleLowerCase().replace(/\s+/g, " ").trim();
  }

  function render(query) {
    const q = normalize(query);
    if (!q) {
      searchResults.hidden = true;
      searchResults.innerHTML = "";
      shown = [];
      return;
    }
    shown = articles
      .map(function (article) {
        const haystack = normalize(
          [article.title, article.summary, article.category].concat(article.aliases || []).join(" ")
        );
        let score = 0;
        if (normalize(article.title).includes(q)) score += 6;
        if ((article.aliases || []).some(function (alias) { return normalize(alias).includes(q); })) score += 4;
        if (haystack.includes(q)) score += 2;
        return { article: article, score: score };
      })
      .filter(function (entry) { return entry.score > 0; })
      .sort(function (a, b) { return b.score - a.score; })
      .slice(0, 7)
      .map(function (entry) { return entry.article; });
    selected = 0;
    searchResults.hidden = false;
    if (!shown.length) {
      searchResults.innerHTML =
        '<div class="search-empty">' +
        (locale === "zh"
          ? "没有匹配结果。可以缩短关键词，或把当前问题和手册首页链接复制给 AI。"
          : "No matching guide yet. Shorten the query, or copy the manual link for an AI assistant.") +
        "</div>";
      return;
    }
    searchResults.innerHTML = shown
      .map(function (article, index) {
        return (
          '<a class="search-result' +
          (index === selected ? " active" : "") +
          '" href="' +
          article.url +
          '"><strong>' +
          article.title +
          "</strong><span>" +
          article.summary +
          "</span><small>" +
          article.category +
          "</small></a>"
        );
      })
      .join("");
  }

  searchInput.addEventListener("input", function () {
    render(searchInput.value);
  });

  searchInput.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      searchInput.value = "";
      render("");
      searchInput.blur();
      return;
    }
    if (!shown.length) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      selected =
        event.key === "ArrowDown"
          ? (selected + 1) % shown.length
          : (selected - 1 + shown.length) % shown.length;
      searchResults.querySelectorAll(".search-result").forEach(function (row, index) {
        row.classList.toggle("active", index === selected);
      });
    }
    if (event.key === "Enter") {
      event.preventDefault();
      location.href = shown[selected].url;
    }
  });

  document.addEventListener("keydown", function (event) {
    if (
      event.key === "/" &&
      document.activeElement !== searchInput &&
      !/input|textarea|select/i.test(document.activeElement.tagName)
    ) {
      event.preventDefault();
      searchInput.focus();
    }
  });

  const initial = new URLSearchParams(location.search).get("q");
  if (initial) {
    searchInput.value = initial;
    render(initial);
    searchInput.focus();
  }
})();
