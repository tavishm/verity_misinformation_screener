"use strict";
const $ = id => document.getElementById(id);
async function send(payload) {
  const result = await chrome.runtime.sendMessage(payload);
  if (!result?.ok) throw new Error(result?.message || "The extension is unavailable.");
  return result.data;
}
function notice(message = "") {$("notice").textContent = message; $("notice").hidden = !message;}
async function refresh() {
  try {
    const value = await send({type: "GET_STATUS"});
    $("status").textContent = value.modelReady ? "Connected · ready to check" : value.connected ? "Connected · model is starting" : "Ready to connect";
    $("source-count").textContent = value.connected ? `${value.documents} source documents available to the local model.` : "Start the local service, then choose Connect local checker.";
    $("dot").className = `dot ${value.modelReady ? "ready" : value.connected ? "partial" : ""}`;
    $("disconnect").disabled = !value.configured;
  } catch (error) {$("status").textContent = "Checker offline"; $("dot").className = "dot"; notice(error.message);}
}
$("connect").addEventListener("click", async () => {
  $("connect").disabled = true; notice();
  try {await send({type: "CONNECT"}); await refresh();} catch (error) {notice(error.message);} finally {$("connect").disabled = false;}
});
$("disconnect").addEventListener("click", async () => {try {await send({type: "DISCONNECT"}); notice("Disconnected. Automatic checking is off."); await refresh();} catch (error) {notice(error.message);}});
$("refresh").addEventListener("click", () => {notice(); refresh();});
$("open").addEventListener("click", () => send({type: "OPEN_DEMO"}).catch(error => notice(error.message)));
refresh();
