const MODE_ORDER = ["confirm", "http-only", "enqueue"];

const SORTS = {
  req_s: {
    label: "Sort: req/s",
    compare: (left, right) => right.req_s - left.req_s,
  },
  p95: {
    label: "Sort: p95",
    compare: (left, right) => left.p95_ms - right.p95_ms,
  },
  efficiency: {
    label: "Sort: req/s / CPU limit",
    compare: (left, right) => right.req_per_cpu_limit - left.req_per_cpu_limit,
  },
  memory: {
    label: "Sort: memory",
    compare: (left, right) => left.mem_avg_mib - right.mem_avg_mib,
  },
};

function formatNumber(value, digits = 2) {
  return Number(value).toLocaleString("en-US", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

function formatPercent(value, digits = 2) {
  return `${formatNumber(value, digits)}%`;
}

function byModeOrder(mode) {
  const index = MODE_ORDER.indexOf(mode);
  return index === -1 ? MODE_ORDER.length : index;
}

function dashboardUrl(snapshotId, mode) {
  const params = new URLSearchParams();
  if (snapshotId) {
    params.set("snapshot", snapshotId);
  }
  if (mode) {
    params.set("mode", mode);
  }
  const suffix = params.toString();
  const base = document.body.dataset.page === "history" ? "../" : "./";
  return `${base}${suffix ? `?${suffix}` : ""}`;
}

function modePill(mode, label) {
  return `<span class="mode-pill" data-mode="${mode}">${label}</span>`;
}

async function loadSiteData() {
  const dataPath = document.body.dataset.dataPath;
  const response = await fetch(dataPath, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`Failed to load benchmark site data: ${response.status}`);
  }
  return response.json();
}

function renderDashboard(data) {
  const snapshots = data.snapshots;
  const snapshotById = new Map(snapshots.map((snapshot) => [snapshot.id, snapshot]));
  const params = new URLSearchParams(window.location.search);
  const requestedSnapshot = snapshotById.get(params.get("snapshot"));
  const requestedMode = params.get("mode");
  const activeSnapshot =
    requestedSnapshot ||
    snapshotById.get(data.latest_by_mode[requestedMode]) ||
    snapshotById.get(data.latest_by_mode.confirm) ||
    snapshots[0];

  if (!activeSnapshot) {
    document.getElementById("context-panel").innerHTML =
      '<p class="empty-state">No benchmark snapshots were found.</p>';
    return;
  }

  let currentSort = params.get("sort");
  if (!SORTS[currentSort]) {
    currentSort = "req_s";
  }

  const snapshotsForMode = snapshots
    .filter((snapshot) => snapshot.mode === activeSnapshot.mode)
    .sort((left, right) => right.timestamp.localeCompare(left.timestamp));

  renderContextPanel(activeSnapshot);
  renderModeTabs(data, activeSnapshot, currentSort);
  renderSnapshotSelect(activeSnapshot, snapshotsForMode, currentSort);
  renderCards(activeSnapshot.cards);
  renderSortControls(activeSnapshot, currentSort);
  renderRankingTable(activeSnapshot.rows, currentSort);
  renderInterpretation(activeSnapshot);
  renderDeltaPanel(activeSnapshot);
  renderRecentSnapshots(snapshots.slice(0, 6));
}

function renderContextPanel(snapshot) {
  const context = document.getElementById("context-panel");
  context.innerHTML = `
    <div class="context-item">
      <strong>Snapshot</strong>
      <span>${snapshot.date_label} ${snapshot.time_label}</span>
    </div>
    <div class="context-item">
      <strong>Mode</strong>
      <span>${modePill(snapshot.mode, snapshot.mode_label)}</span>
    </div>
    <div class="context-item">
      <strong>Git SHA</strong>
      <a href="${snapshot.commit_url}" target="_blank" rel="noreferrer">${snapshot.git_sha_short}</a>
    </div>
    <div class="context-item">
      <strong>Workload</strong>
      <span>${snapshot.workload}</span>
    </div>
    <div class="context-item">
      <strong>Budget</strong>
      <span>${snapshot.budget}</span>
    </div>
    <div class="context-item">
      <strong>Services</strong>
      <span>${snapshot.services_count} lanes</span>
    </div>
  `;
}

function renderModeTabs(data, activeSnapshot, currentSort) {
  const container = document.getElementById("mode-tabs");
  const availableModes = [...new Set(data.snapshots.map((snapshot) => snapshot.mode))].sort(
    (left, right) => byModeOrder(left) - byModeOrder(right)
  );
  container.innerHTML = availableModes
    .map((mode) => {
      const latestId = data.latest_by_mode[mode];
      const latestSnapshot = data.snapshots.find((snapshot) => snapshot.id === latestId);
      const active = mode === activeSnapshot.mode ? "active" : "";
      const title = latestSnapshot ? `${latestSnapshot.mode_label} · ${latestSnapshot.date_label}` : mode;
      return `<button class="mode-tab ${active}" data-mode="${mode}" title="${title}">${
        latestSnapshot ? latestSnapshot.mode_label : mode
      }</button>`;
    })
    .join("");

  container.querySelectorAll(".mode-tab").forEach((button) => {
    button.addEventListener("click", () => {
      const mode = button.dataset.mode;
      const latestId = data.latest_by_mode[mode];
      const params = new URLSearchParams();
      params.set("mode", mode);
      if (latestId) {
        params.set("snapshot", latestId);
      }
      if (currentSort !== "req_s") {
        params.set("sort", currentSort);
      }
      window.location.search = params.toString();
    });
  });
}

function renderSnapshotSelect(activeSnapshot, snapshotsForMode, currentSort) {
  const select = document.getElementById("snapshot-select");
  select.innerHTML = snapshotsForMode
    .map(
      (snapshot) => `
        <option value="${snapshot.id}" ${snapshot.id === activeSnapshot.id ? "selected" : ""}>
          ${snapshot.date_label} ${snapshot.time_label} · ${snapshot.git_sha_short}
        </option>
      `
    )
    .join("");

  select.onchange = () => {
    const params = new URLSearchParams();
    params.set("snapshot", select.value);
    params.set("mode", activeSnapshot.mode);
    if (currentSort !== "req_s") {
      params.set("sort", currentSort);
    }
    window.location.search = params.toString();
  };
}

function renderCards(cards) {
  const grid = document.getElementById("metric-cards");
  grid.innerHTML = Object.values(cards)
    .map(
      (card) => `
        <article class="panel metric-card">
          <p class="metric-label">${card.label}</p>
          <p class="metric-service">${card.service_label}</p>
          <p class="metric-value">${card.metric}</p>
        </article>
      `
    )
    .join("");
}

function renderSortControls(snapshot, currentSort) {
  const controls = document.getElementById("sort-controls");
  controls.innerHTML = Object.entries(SORTS)
    .map(([key, sort]) => {
      const active = key === currentSort ? "active" : "";
      return `<button class="sort-chip ${active}" data-sort="${key}">${sort.label}</button>`;
    })
    .join("");

  controls.querySelectorAll(".sort-chip").forEach((button) => {
    button.addEventListener("click", () => {
      const params = new URLSearchParams(window.location.search);
      params.set("snapshot", snapshot.id);
      params.set("mode", snapshot.mode);
      params.set("sort", button.dataset.sort);
      window.location.search = params.toString();
    });
  });
}

function renderRankingTable(rows, currentSort) {
  const tableBody = document.querySelector("#ranking-table tbody");
  const orderedRows = [...rows].sort(SORTS[currentSort].compare);
  tableBody.innerHTML = orderedRows
    .map(
      (row, index) => `
        <tr>
          <td><span class="rank-pill">${index + 1}</span></td>
          <td class="service-name">${row.service_label}</td>
          <td>${formatNumber(row.req_s)}</td>
          <td>${formatNumber(row.p95_ms)} ms</td>
          <td>${formatNumber(row.req_per_cpu_limit)}</td>
          <td>${formatNumber(row.mem_avg_mib)} MiB</td>
          <td>${formatNumber(row.cpu_avg_pct)}%</td>
        </tr>
      `
    )
    .join("");
}

function renderInterpretation(snapshot) {
  const notes = document.getElementById("interpretation-list");
  notes.innerHTML = snapshot.interpretation.map((line) => `<li>${line}</li>`).join("");
  const miniMeta = document.getElementById("snapshot-meta");
  miniMeta.innerHTML = `
    <div><strong>Top throughput:</strong> ${snapshot.top_service_label} (${formatNumber(snapshot.top_req_s)} req/s)</div>
    <div><strong>Services:</strong> ${snapshot.services_count} receiver lanes</div>
    <div><strong>Commit:</strong> <a href="${snapshot.commit_url}" target="_blank" rel="noreferrer">${snapshot.git_sha_short}</a></div>
  `;
}

function renderDeltaPanel(snapshot) {
  const panel = document.getElementById("delta-panel");
  const comparison = snapshot.mode_comparison;
  if (!comparison || !comparison.rows.length) {
    panel.innerHTML = `
      <div class="section-head">
        <div>
          <p class="eyebrow">Matched Delta</p>
          <h2>No paired mode snapshot</h2>
        </div>
      </div>
      <p class="empty-state">
        This snapshot does not currently have matched mode-comparison data available.
      </p>
    `;
    return;
  }

  panel.innerHTML = `
    <div class="section-head">
      <div>
        <p class="eyebrow">Matched Delta</p>
        <h2>Compared with ${comparison.matched_delivery_mode}</h2>
      </div>
    </div>
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>Current rank</th>
            <th>Service</th>
            <th>Retained throughput</th>
            <th>Lost throughput</th>
            <th>Added latency</th>
            <th>HTTP-only req/s</th>
            <th>Paired req/s</th>
          </tr>
        </thead>
        <tbody>
          ${comparison.rows
            .map(
              (row) => `
                <tr>
                  <td><span class="rank-pill">${row.current_rank}</span></td>
                  <td class="service-name">${row.service_label}</td>
                  <td>${formatPercent(row.retained_pct)}</td>
                  <td>${formatPercent(row.lost_pct)}</td>
                  <td>${formatNumber(row.added_latency_ms)} ms</td>
                  <td>${formatNumber(row.http_only_req_s)}</td>
                  <td>${formatNumber(row.paired_req_s)}</td>
                </tr>
              `
            )
            .join("")}
        </tbody>
      </table>
    </div>
  `;
}

function renderRecentSnapshots(snapshots) {
  const panel = document.getElementById("recent-panel");
  panel.innerHTML = `
    <div class="section-head">
      <div>
        <p class="eyebrow">Recent Snapshots</p>
        <h2>Latest published snapshots</h2>
      </div>
      <a class="link-button" href="history/">Open history</a>
    </div>
    <div class="recent-list">
      ${snapshots
        .map(
          (snapshot) => `
            <article class="recent-item">
              <div>
                <div class="recent-title">${snapshot.date_label} ${snapshot.time_label}</div>
                <div class="recent-meta">
                  ${modePill(snapshot.mode, snapshot.mode_label)}
                  <span>${snapshot.git_sha_short}</span>
                  <span>${snapshot.top_service_label} led raw req/s</span>
                </div>
              </div>
              <div class="inline-actions">
                <a href="${dashboardUrl(snapshot.id, snapshot.mode)}">Open dashboard</a>
                <a href="${snapshot.commit_url}" target="_blank" rel="noreferrer">Commit</a>
              </div>
            </article>
          `
        )
        .join("")}
    </div>
  `;
}

function renderHistory(data) {
  const snapshots = [...data.snapshots].sort((left, right) => right.timestamp.localeCompare(left.timestamp));
  document.getElementById("history-context").innerHTML = `
    <div class="context-item">
      <strong>Snapshots</strong>
      <span>${snapshots.length}</span>
    </div>
    <div class="context-item">
      <strong>Modes covered</strong>
      <span>${[...new Set(snapshots.map((snapshot) => snapshot.mode_label))].join(", ")}</span>
    </div>
    <div class="context-item">
      <strong>Latest confirm</strong>
      <span>${data.latest_by_mode.confirm || "n/a"}</span>
    </div>
    <div class="context-item">
      <strong>Repo docs</strong>
      <a href="${data.docs.history_url}" target="_blank" rel="noreferrer">Benchmark history doc</a>
    </div>
  `;

  const tbody = document.querySelector("#history-table tbody");
  tbody.innerHTML = snapshots
    .map(
      (snapshot) => `
        <tr>
          <td>${snapshot.date_label} ${snapshot.time_label}</td>
          <td>${modePill(snapshot.mode, snapshot.mode_label)}</td>
          <td>${snapshot.git_sha_short}</td>
          <td>${snapshot.top_service_label} (${formatNumber(snapshot.top_req_s)} req/s)</td>
          <td>${snapshot.workload}<br /><span class="mini-meta">${snapshot.budget}</span></td>
          <td>
            <div class="inline-actions">
              <a href="${dashboardUrl(snapshot.id, snapshot.mode)}">Dashboard</a>
              <a href="${snapshot.commit_url}" target="_blank" rel="noreferrer">Commit</a>
            </div>
          </td>
        </tr>
      `
    )
    .join("");
}

async function init() {
  const page = document.body.dataset.page;
  if (!page) {
    return;
  }

  try {
    const data = await loadSiteData();
    if (page === "dashboard") {
      renderDashboard(data);
      return;
    }
    if (page === "history") {
      renderHistory(data);
    }
  } catch (error) {
    const target =
      document.getElementById("context-panel") ||
      document.getElementById("history-context") ||
      document.querySelector(".page-shell");
    if (target) {
      const message = document.createElement("p");
      message.className = "empty-state";
      message.textContent = error instanceof Error ? error.message : String(error);
      target.replaceChildren(message);
    }
  }
}

init();
