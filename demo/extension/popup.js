"use strict";
const $ = id => document.getElementById(id);
async function send(payload) {
  const response = await chrome.runtime.sendMessage(payload);
  if (!response?.ok) throw new Error(response?.message || "The extension is unavailable.");
  return response.data;
}
function notice(message = "") {$("notice").textContent = message; $("notice").hidden = !message;}
function preferences(value) {
  $("auto").checked = value.autoCheckEnabled;
  $("auto").disabled = !value.configured;
  $("capture").checked = value.feedCaptureEnabled === true;
  $("capture").disabled = !value.configured || !value.autoCheckEnabled;
  $("mode").value = value.autoMode; $("limit").value = String(value.dailyLimit);
  $("remaining").textContent = `${value.autoUsed} of ${value.dailyLimit} new automatic checks used today. Cached results are free to reuse.`;
  $("mode-hint").textContent = value.autoMode === "popular" ? "X: at least 1M views · Reddit: at least 1,000 votes. Counts must be visible." : "Checks begin when posts enter your screen. Manual checks remain available.";
}
async function refresh() {
  try {
    preferences(await send({type: "GET_SETTINGS"}));
    const value = await send({type: "GET_STATUS"});
    preferences(value);
    $("status").textContent = value.modelReady ? "Ready to check" : value.connected ? "Model is starting" : "Connect your checker";
    $("dot").className = `dot ${value.modelReady ? "ready" : value.connected ? "partial" : ""}`;
    $("source-count").textContent = value.connected ? `${value.documents} source documents · local model` : "Start the local service, then connect";
    $("connect").textContent = value.connected ? "Reconnect" : "Connect";
  } catch (error) {$("status").textContent = "Checker offline"; $("dot").className = "dot"; notice(error.message);}
}
$("connect").addEventListener("click", async () => {
  $("connect").disabled = true; notice();
  try {await send({type: "CONNECT"}); await refresh();} catch (error) {notice(error.message);} finally {$("connect").disabled = false;}
});
for (const id of ["auto", "capture", "mode", "limit"]) $(id).addEventListener("change", async () => {
  notice();
  try {preferences(await send({type: "SET_SETTINGS", autoCheckEnabled: $("auto").checked, feedCaptureEnabled: $("capture").checked, autoMode: $("mode").value, dailyLimit: Number($("limit").value)}));}
  catch (error) {notice(error.message); preferences(await send({type: "GET_SETTINGS"}));}
});
$("refresh").addEventListener("click", () => {notice(); refresh();});
$("open").addEventListener("click", () => send({type: "OPEN_DEMO"}).catch(error => notice(error.message)));
$("dashboard").addEventListener("click", () => send({type: "OPEN_DASHBOARD"}).catch(error => notice(error.message)));
$("settings").addEventListener("click", () => send({type: "OPEN_OPTIONS"}).catch(error => notice(error.message)));
refresh();
