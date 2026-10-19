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
    if (tab.dataset.go === "config") { loadConfig(); loadSettings(); }
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
  readAuth(text);
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
    showFound(file.found);
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

async function savePlaylist(anyway) {
  try {
    const saved = await call("/api/playlists/" + encodeURIComponent(editing),
      { method: "PUT",
        body: JSON.stringify({ text: $("plText").value, anyway }) });
    loadedText = $("plText").value;
    showFound(saved.found);
    say($("plMsg"), "good", "Saved. " + saved.lines + " track line(s)"
      + (saved.backup ? ", backup <code>" + saved.backup + "</code>" : "") + ".");
    refresh();
  } catch (problem) {
    const data = problem.data || {};
    showFound(data.found);
    say($("plMsg"), "err", (data.errors
        ? data.errors + " line(s) would be searched for as something else. Nothing written."
        : problem.message)
      + ' <button class="btn" id="plForce">Save anyway</button>');
    const force = document.getElementById("plForce");
    if (force) force.onclick = () => savePlaylist(true);
  }
}
$("plSave").onclick = () => savePlaylist(false);
$("plRevert").onclick = () => {
  $("plText").value = loadedText;
  $("plMsg").hidden = true;
  showFound(lint(loadedText));
};
$("plText").oninput = () => showFound(lint($("plText").value));

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

/* ---------------------------------------------------------------- the settings form
 *
 * Nothing about the settings is written here: /api/settings describes every category and
 * every field - its type, its help, and what the file says today - and this draws it. A
 * setting added in Java turns up on this page by itself, with its own explanation.
 */

const changes = new Map();
let described = null;

document.querySelectorAll(".tab[data-cfg]").forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll(".tab[data-cfg]").forEach((other) =>
      other.classList.toggle("on", other === tab));
    $("cfgForm").hidden = tab.dataset.cfg !== "form";
    $("cfgRawBox").hidden = tab.dataset.cfg !== "raw";
  };
});

async function loadSettings() {
  try {
    described = await call("/api/settings");
    changes.clear();
    drawSettings();
  } catch (problem) {
    say($("cfgMsg"), "err", problem.message);
  }
}

function drawSettings() {
  const box = $("cfgFields");
  box.innerHTML = "";
  described.categories.forEach((category) => {
    if (!category.fields.length) return;
    const group = document.createElement("div");
    group.className = "group";
    const head = document.createElement("h3");
    const title = document.createElement("span");
    title.textContent = category.title;
    const help = document.createElement("em");
    help.textContent = category.help;
    head.appendChild(title);
    head.appendChild(help);
    group.appendChild(head);
    const rows = document.createElement("div");
    rows.className = "rows";
    category.fields.forEach((field) => rows.appendChild(row(field)));
    group.appendChild(rows);
    box.appendChild(group);
  });
  countChanges();
}

function row(field) {
  const line = document.createElement("div");
  line.className = "rowf";
  line.dataset.path = field.path;

  const name = document.createElement("div");
  name.className = "name";
  const bold = document.createElement("b");
  bold.textContent = field.label;
  name.appendChild(bold);
  if (!field.set && field.fallback !== undefined) {
    const dim = document.createElement("i");
    dim.textContent = " not set, " + field.fallback;
    name.appendChild(dim);
  }
  line.appendChild(name);

  line.appendChild(field.kind === "ladder" ? ladderBox(field)
    : field.kind === "boolean" ? switchBox(field) : textBox(field));

  const why = document.createElement("div");
  why.className = "why";
  why.textContent = field.help;
  line.appendChild(why);
  return line;
}

function mark(path, value, original) {
  if (value === original) changes.delete(path); else changes.set(path, value);
  document.querySelectorAll(".rowf").forEach((one) => {
    if (one.dataset.path === path) one.classList.toggle("changed", changes.has(path));
  });
  countChanges();
}

function countChanges() {
  const many = changes.size;
  $("cfgApply").disabled = many === 0;
  $("cfgDrop").disabled = many === 0;
  $("cfgCount").textContent = many === 0
    ? "Nothing changed yet. The comments in the file are kept."
    : many + " change(s) ready. Only those lines are rewritten; the rest of the file, "
      + "comments included, is left alone.";
}

function textBox(field) {
  const wrap = document.createElement("div");
  const input = document.createElement("input");
  input.type = "text";
  input.value = field.value;
  input.placeholder = field.fallback === undefined ? "" : String(field.fallback);
  input.oninput = () => mark(field.path, input.value.trim(), field.value);
  wrap.appendChild(input);
  return wrap;
}

function switchBox(field) {
  const wrap = document.createElement("label");
  wrap.className = "switch";
  const box = document.createElement("input");
  box.type = "checkbox";
  box.checked = /^(true|yes|on|1)$/i.test(field.value);
  const text = document.createElement("span");
  text.textContent = box.checked ? "on" : "off";
  box.onchange = () => {
    text.textContent = box.checked ? "on" : "off";
    mark(field.path, String(box.checked), field.value);
  };
  wrap.appendChild(box);
  wrap.appendChild(text);
  return wrap;
}

/* The ladder is the one setting that is a list: "mp3@256, flac, mp3@128" is three rungs,
 * and a rung is a format with an optional floor. The pass that takes anything at all is
 * always there and is not editable - that is what keeps the ladder from losing tracks. */
function ladderBox(field) {
  const wrap = document.createElement("div");
  wrap.className = "ladder";
  const rungs = field.value.split(",").map((one) => one.trim()).filter(Boolean);
  const push = () => mark(field.path, rungs.filter(Boolean).join(", "), field.value);

  const redraw = () => {
    wrap.innerHTML = "";
    rungs.forEach((rung, at) => {
      const step = document.createElement("div");
      step.className = "rung";
      const input = document.createElement("input");
      input.type = "text";
      input.value = rung;
      input.placeholder = "mp3@256";
      input.oninput = () => { rungs[at] = input.value.trim(); push(); };
      const drop = document.createElement("button");
      drop.className = "mini";
      drop.textContent = "remove";
      drop.onclick = () => { rungs.splice(at, 1); redraw(); push(); };
      step.appendChild(input);
      step.appendChild(drop);
      wrap.appendChild(step);
    });
    const add = document.createElement("button");
    add.className = "mini";
    add.textContent = "+ pass";
    add.onclick = () => { rungs.push(""); redraw(); push(); };
    wrap.appendChild(add);
    const last = document.createElement("div");
    last.className = "last";
    last.textContent = "then one last pass that takes anything at all - always, so nothing "
      + "is lost";
    wrap.appendChild(last);
  };
  redraw();
  return wrap;
}

$("cfgDrop").onclick = () => { changes.clear(); drawSettings(); $("cfgMsg").hidden = true; };

async function applySettings(anyway) {
  try {
    const sent = Object.fromEntries(changes);
    const saved = await call("/api/settings",
      { method: "PUT", body: JSON.stringify({ changes: sent, anyway }) });
    say($("cfgMsg"), "good", "Saved " + Object.keys(sent).length + " change(s)"
      + (saved.backup ? ", backup <code>" + saved.backup + "</code>" : "") + ".");
    await loadSettings();
    loadConfig();
    refresh();
  } catch (problem) {
    const warnings = (problem.data && problem.data.warnings) || [problem.message];
    say($("cfgMsg"), "err", "Not written:<ul>"
      + warnings.map((warning) => "<li>" + warning + "</li>").join("") + "</ul>"
      + '<button class="btn" id="cfgForce">Save anyway</button>');
    $("cfgForce").onclick = () => applySettings(true);
  }
}
$("cfgApply").onclick = () => applySettings(false);

/* ---------------------------------------------------------------- what the checker found */

function showFound(found) {
  const box = $("plFound");
  if (!found || !found.length) { box.hidden = true; return; }
  box.innerHTML = "";
  found.forEach((one) => {
    const line = document.createElement("div");
    line.className = one.level;
    const number = document.createElement("span");
    number.className = "ln";
    number.textContent = one.line;
    const why = document.createElement("span");
    why.className = "msg2";
    why.textContent = one.why + (one.text ? "  -  " + one.text : "");
    line.appendChild(number);
    line.appendChild(why);
    box.appendChild(line);
  });
  box.hidden = false;
}

/* The same rules as the server's, so the editor answers while you type. The server checks
 * again on save and is the one that decides. */
function lint(text) {
  const found = [];
  const seen = new Map();
  const lines = text.split("\n");
  lines.forEach((raw, at) => {
    const line = raw.trim();
    const number = at + 1;
    if (!line) {
      if (at < lines.length - 1) found.push({ line: number, level: "note", text: "", why: "blank line" });
      return;
    }
    if (line.startsWith("#")) return;
    const bar = line.lastIndexOf("|");
    if (bar >= 0) {
      const tail = line.slice(bar + 1).trim();
      if (!/^(\d+:)?\d{1,2}:\d{1,2}$|^\d+(\.\d+)?$/.test(tail)) {
        found.push({ line: number, level: "error", text: line,
          why: 'the length "' + tail + '" did not parse, so the whole line including the bar '
            + "is searched for as the title" });
        return;
      }
      if (!line.slice(0, bar).trim()) {
        found.push({ line: number, level: "error", text: line,
          why: "a length with no track in front of it" });
        return;
      }
    }
    if (!line.includes(" - ")) {
      found.push({ line: number, level: "warn", text: line,
        why: 'no "Artist - Title": the whole line is searched as one title, which finds less' });
    }
    if (bar < 0) {
      found.push({ line: number, level: "note", text: line,
        why: "no length: it is searched without a length filter" });
    }
    const key = (bar >= 0 ? line.slice(0, bar) : line).trim().toLowerCase();
    if (seen.has(key)) {
      found.push({ line: number, level: "note", text: line,
        why: "the same track as line " + seen.get(key) + "; the run searches it once" });
    } else {
      seen.set(key, number);
    }
  });
  return found;
}

/* ---------------------------------------------------------------- the login, checked */

$("logCheck").onclick = async () => {
  $("logCheck").disabled = true;
  say($("logMsg"), "info", "Asking Soulseek with one search…");
  try {
    const answer = await call("/api/login/check", { method: "POST" });
    if (answer.login === "in") {
      say($("logMsg"), "good", "Logged in as " + answer.about + ".");
    } else if (answer.login === "out") {
      say($("logMsg"), "err", "The login did not work: " + answer.about);
    } else {
      say($("logMsg"), "err", "sockseek said nothing about the login this time. "
        + "The live log on the Run page has whatever it did say.");
    }
    setAuth(answer.login, answer.about);
  } catch (problem) {
    say($("logMsg"), "err", problem.message);
  } finally {
    $("logCheck").disabled = false;
  }
};

/* The badge in the header. A run prints "auth: ..." the moment sockseek says it, so the
 * page reads it from the same line the person reads. */
function setAuth(state, about) {
  const badge = $("auth");
  badge.className = "badge" + (state === "in" ? " in" : state === "out" ? " out" : "");
  badge.textContent = state === "in" ? "signed in as " + about
    : state === "out" ? "sign-in failed" : "auth unknown";
  badge.title = state === "out" ? about
    : "Soulseek sign-in, as sockseek itself reports it";
}

function readAuth(text) {
  const ok = text.match(/^auth: logged in as (.+)$/);
  if (ok) setAuth("in", ok[1]);
  const bad = text.match(/^auth: LOGIN FAILED - (.+)$/);
  if (bad) setAuth("out", bad[1]);
}
