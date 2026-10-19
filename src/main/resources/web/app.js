/*
 * The page. It holds no state of its own that matters: the folder is the truth, the server
 * answers for it, and every number on screen comes from a line the engine printed or from
 * /api/state. The counters are read off the log for one reason - the console prints
 * "pass 1 of 4" and ">> 12 of 18 done, 7 downloaded" already, so a second channel carrying
 * the same numbers would be a second thing that can disagree with the log in front of you.
 */
const KEY = new URLSearchParams(location.search).get("t") || "";
const $ = (id) => document.getElementById(id);

let STATE = { playlists: [], passes: 1, perWindow: 34, windowSeconds: 220, running: false };
const picked = new Set();
let started = 0, clock = null, editing = null, loadedText = "";

/* ---------------------------------------------------------------- talking to the tool */

async function call(path, options = {}) {
  const headers = Object.assign({ "X-Sockseek-Key": KEY }, options.headers || {});
  if (options.body) headers["Content-Type"] = "application/json";
  const answer = await fetch(path, Object.assign({}, options, { headers }));
  const text = await answer.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (notJson) { data = { error: text }; }
  if (!answer.ok) throw Object.assign(new Error(data.error || answer.status), { data });
  return data;
}

function banner(message) {
  const box = $("banner");
  box.textContent = message || "";
  box.hidden = !message;
}

function say(box, kind, html) {
  box.className = "msg " + kind;
  box.innerHTML = html;
  box.hidden = false;
}

/* ---------------------------------------------------------------- the pages */

document.querySelectorAll("nav button").forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll("nav button").forEach((other) =>
      other.classList.toggle("on", other === tab));
    document.querySelectorAll(".page").forEach((page) =>
      page.classList.toggle("on", page.id === "p-" + tab.dataset.go));
    if (tab.dataset.go === "config") loadConfig();
  };
});

/* ---------------------------------------------------------------- Run */

function drawState() {
  $("where").textContent = STATE.folder || "local";
  $("where").title = STATE.folder || "";
  $("dot").className = "dot" + (STATE.running ? " run" : "");
  $("state").textContent = STATE.running
    ? (STATE.stopping ? "stopping" : (STATE.dryRun ? "dry run" : "running")) : "idle";
  $("plSummary").textContent = STATE.playlists.length + " files, " + STATE.tracks + " tracks";
  $("fileCount").textContent = STATE.playlists.length;

  const rows = $("plTable").tBodies[0];
  rows.innerHTML = "";
  STATE.playlists.forEach((entry) => {
    const missing = entry.missing;
    const on = picked.has(entry.name);
    const row = rows.insertRow();
    row.className = (on ? "sel " : "") + (entry.unreadable ? "dead" : "");
    row.innerHTML = `<td><input type="checkbox" tabindex="-1" ${on ? "checked" : ""}></td>`
      + `<td></td><td>${entry.tracks}</td><td>${entry.here}</td>`
      + `<td class="miss ${missing ? "" : "zero"}">${missing || "—"}</td>`;
    row.cells[1].textContent = entry.unreadable ? entry.name + " (cannot be read)" : entry.name;
    row.onclick = () => {
      if (picked.has(entry.name)) picked.delete(entry.name); else picked.add(entry.name);
      drawState();
    };
  });

  const chosen = STATE.playlists.filter((entry) => picked.has(entry.name));
  const missing = chosen.reduce((sum, entry) => sum + entry.missing, 0);
  const searches = missing * (STATE.passes || 1);
  const minutes = STATE.perWindow > 0
    ? Math.round(searches / STATE.perWindow * STATE.windowSeconds / 60) : 0;
  $("price").innerHTML = missing
    ? `Selected <b>${chosen.length}</b> playlist(s), <b>${missing}</b> missing track(s).<br>`
      + `<b>${STATE.passes}</b> pass(es) over every missing track, so about `
      + `<b>${searches}</b> searches. At ${STATE.perWindow} per ${STATE.windowSeconds} s the `
      + `limiter alone adds about <b>${minutes} min</b>; searching and downloading come on top.`
    : (chosen.length ? "Nothing missing in what you picked."
                     : "Pick a playlist. Ticking none and pressing Download takes them all.");

  const busy = STATE.running;
  $("go").disabled = busy;
  $("dry").disabled = busy;
  $("stop").disabled = !busy;
  $("all").checked = picked.size > 0 && picked.size === STATE.playlists.length;
  $("plSave").disabled = !editing || busy;
  $("plRevert").disabled = !editing;
  if (!STATE.loginSet) {
    banner("No Soulseek login yet - nothing can be downloaded until there is one. "
      + "The Login page has two fields.");
  } else {
    banner(STATE.warnings && STATE.warnings.length
      ? "config.yaml: " + STATE.warnings.join(" | ") : "");
  }
  $("logState").textContent = STATE.loginSet
    ? "A login is set. Enter it again only to replace it."
    : "No login yet. Nothing can be downloaded until there is one.";
  $("logState").className = "msg " + (STATE.loginSet ? "info" : "err");
  drawFiles();
}

async function refresh() {
  try {
    STATE = await call("/api/state");
    drawState();
  } catch (problem) {
    banner("Cannot reach the tool: " + problem.message
      + ". It may have been stopped in the console.");
    $("dot").className = "dot off";
    $("state").textContent = "no connection";
  }
}

$("all").onclick = (event) => {
  picked.clear();
  if (event.target.checked) STATE.playlists.forEach((entry) => picked.add(entry.name));
  drawState();
};

async function start(dry) {
  resetCounters();
  try {
    await call("/api/run", {
      method: "POST",
      body: JSON.stringify({ playlists: [...picked], dryRun: dry }),
    });
  } catch (problem) {
    line("bad", "--- " + problem.message);
  }
  refresh();
}
$("go").onclick = () => start(false);
$("dry").onclick = () => start(true);
$("stop").onclick = async () => {
  $("stop").disabled = true;
  try { await call("/api/stop", { method: "POST" }); }
  catch (problem) { line("bad", "--- " + problem.message); }
};

/* ---------------------------------------------------------------- the live log */

function kind(text) {
  if (/^(FAILED|ERROR|\s*-> )/.test(text)) return "bad";
  if (/^(OK |\+ )/.test(text)) return "ok";
  if (/^\^ /.test(text)) return "up";
  if (/^(pass \d|pausing |WARN |---)/.test(text)) return "warn";
  if (/^(\[\d+\/\d+\]|run #|Playlists:|To download:)/.test(text)) return "head";
  if (/^>> /.test(text)) return "dim";
  return "";
}

function line(cls, text) {
  const log = $("log");
  const atEnd = log.scrollTop + log.clientHeight >= log.scrollHeight - 40;
  const row = document.createElement("div");
  row.className = cls === undefined ? kind(text) : cls;
  row.textContent = text;
  log.appendChild(row);
  while (log.childElementCount > 4000) log.removeChild(log.firstChild);
  if (atEnd) log.scrollTop = log.scrollHeight;
}

function resetCounters() {
  $("log").innerHTML = "";
  $("c-pass").textContent = "—";
  $("c-done").textContent = "0 of 0";
  $("c-ok").textContent = "0";
  $("c-bad").textContent = "0";
  $("bar").style.width = "0";
  started = Date.now();
  if (clock) clearInterval(clock);
  clock = setInterval(() => {
    if (!STATE.running) return;
    const seconds = Math.floor((Date.now() - started) / 1000);
    $("c-time").textContent = Math.floor(seconds / 60) + ":"
      + String(seconds % 60).padStart(2, "0");
  }, 1000);
}

function count(text) {
  const done = text.match(/^>> (\d+) of (\d+) done, (\d+) downloaded/);
  if (done) {
    $("c-done").textContent = done[1] + " of " + done[2];
    $("c-ok").textContent = done[3];
    $("c-bad").textContent = Number(done[1]) - Number(done[3]);
    $("bar").style.width = (Number(done[1]) / Number(done[2]) * 100) + "%";
  }
  const pass = text.match(/^pass (\d+) of (\d+)/);
  if (pass) $("c-pass").textContent = pass[1] + " of " + pass[2];
  if (/^run #\d+/.test(text)) $("runno").textContent = text.split(":")[0];
}

function listen() {
  const feed = new EventSource("/api/stream?t=" + encodeURIComponent(KEY));
  feed.addEventListener("log", (event) => {
    event.data.split("\n").forEach((text) => { line(undefined, text); count(text); });
  });
  feed.addEventListener("state", (event) => {
    try { STATE = JSON.parse(event.data); drawState(); } catch (notJson) { refresh(); }
  });
  feed.onopen = () => { if (STATE.loginSet) banner(""); };
  feed.onerror = () => {
    // EventSource reconnects by itself; the state is re-read when it comes back
    $("dot").className = "dot off";
    $("state").textContent = "reconnecting";
    setTimeout(refresh, 3000);
  };
}

/* ---------------------------------------------------------------- Playlists */

function drawFiles() {
  const box = $("files");
  box.innerHTML = "";
  STATE.playlists.forEach((entry) => {
    const button = document.createElement("button");
    button.className = entry.name === editing ? "on" : "";
    button.innerHTML = `<span></span><span class="n">${entry.tracks}</span>`;
    button.firstChild.textContent = entry.name + ".txt";
    button.onclick = () => openPlaylist(entry.name);
    box.appendChild(button);
  });
}

async function openPlaylist(name) {
  try {
    const file = await call("/api/playlists/" + encodeURIComponent(name));
    editing = name;
    loadedText = file.text;
    $("plName").textContent = name + ".txt";
    $("plText").value = file.text;
    $("plText").disabled = false;
    $("plMsg").hidden = true;
    drawFiles();
    drawState();
  } catch (problem) {
    say($("plMsg"), "err", problem.message);
  }
}

$("plSave").onclick = async () => {
  try {
    const saved = await call("/api/playlists/" + encodeURIComponent(editing),
      { method: "PUT", body: JSON.stringify({ text: $("plText").value }) });
    loadedText = $("plText").value;
    say($("plMsg"), "good", "Saved. " + saved.lines + " track line(s)"
      + (saved.backup ? ", backup <code>" + saved.backup + "</code>" : "") + ".");
    refresh();
  } catch (problem) {
    say($("plMsg"), "err", problem.message);
  }
};
$("plRevert").onclick = () => { $("plText").value = loadedText; $("plMsg").hidden = true; };

$("newFile").onclick = async () => {
  const name = $("newName").value.trim();
  if (!name) return;
  try {
    await call("/api/playlists/" + encodeURIComponent(name),
      { method: "PUT", body: JSON.stringify({ text: "" }) });
    $("newName").value = "";
    await refresh();
    openPlaylist(name.replace(/\.txt$/i, ""));
  } catch (problem) {
    say($("plMsg"), "err", problem.message);
  }
};

/* ---------------------------------------------------------------- Add by link */

$("linkGo").onclick = async () => {
  const url = $("linkIn").value.trim();
  if (!url) return;
  $("linkGo").disabled = true;
  $("linkOut").hidden = true;
  say($("linkMsg"), "info", "Reading " + url);
  try {
    const read = await call("/api/link", {
      method: "POST",
      body: JSON.stringify({
        url,
        track: $("linkTrack").checked,
        overwrite: $("linkOver").checked,
      }),
    });
    $("linkOut").textContent = read.printed.join("\n");
    $("linkOut").hidden = false;
    say($("linkMsg"), "good", "Done. The Run page now shows it.");
    refresh();
  } catch (problem) {
    const printed = (problem.data && problem.data.printed) || [];
    $("linkOut").textContent = printed.join("\n");
    $("linkOut").hidden = printed.length === 0;
    say($("linkMsg"), "err", problem.message);
  } finally {
    $("linkGo").disabled = false;
  }
};

/* ---------------------------------------------------------------- Config */

let configLoaded = "";

async function loadConfig() {
  try {
    const file = await call("/api/config");
    configLoaded = file.text;
    $("cfgText").value = file.text;
    $("cfgPath").textContent = file.path;
    if (file.warnings && file.warnings.length) {
      say($("cfgMsg"), "err", "The file as it is now:<ul>"
        + file.warnings.map((warning) => "<li>" + warning + "</li>").join("") + "</ul>");
    } else {
      $("cfgMsg").hidden = true;
    }
  } catch (problem) {
    say($("cfgMsg"), "err", problem.message);
  }
}

async function saveConfig(anyway) {
  try {
    const saved = await call("/api/config",
      { method: "PUT", body: JSON.stringify({ text: $("cfgText").value, anyway }) });
    configLoaded = $("cfgText").value;
    say($("cfgMsg"), "good", "Saved"
      + (saved.backup ? ", backup <code>" + saved.backup + "</code>" : "")
      + ". A run already going keeps the settings it started with.");
    refresh();
  } catch (problem) {
    const warnings = (problem.data && problem.data.warnings) || [problem.message];
    say($("cfgMsg"), "err", "Not written:<ul>"
      + warnings.map((warning) => "<li>" + warning + "</li>").join("") + "</ul>"
      + '<button class="btn" id="cfgAnyway">Save anyway</button>');
    $("cfgAnyway").onclick = () => saveConfig(true);
  }
}
$("cfgSave").onclick = () => saveConfig(false);
$("cfgRevert").onclick = () => { $("cfgText").value = configLoaded; $("cfgMsg").hidden = true; };

/* ---------------------------------------------------------------- Login */

$("logSave").onclick = async () => {
  try {
    await call("/api/login", {
      method: "POST",
      body: JSON.stringify({ username: $("user").value, password: $("pass").value }),
    });
    $("user").value = "";
    $("pass").value = "";
    say($("logMsg"), "good", "Saved to config.local.yaml.");
    refresh();
  } catch (problem) {
    say($("logMsg"), "err", problem.message);
  }
};

/* ---------------------------------------------------------------- start */

if (!KEY) {
  banner("This address has no key in it. Start the tool with --web again and open the address "
    + "it prints.");
} else {
  refresh().then(listen);
}
