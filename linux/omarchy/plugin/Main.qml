import QtQuick
import Quickshell
import Quickshell.Io

Item {
  id: root
  visible: false

  property var settings: ({})

  readonly property string runtimeDir: Quickshell.env("XDG_RUNTIME_DIR") || ""
  readonly property string homeDir: Quickshell.env("HOME") || ""
  readonly property string controlPath: runtimeDir !== ""
    ? (runtimeDir + "/klardrop/control.json")
    : (homeDir + "/.cache/klardrop/control.json")

  property bool daemonRunning: false
  property int controlPort: 0
  property string controlToken: ""
  property var state: null
  property int stateVersion: 0
  property int backoffMs: 1000
  property var activePollXhr: null
  // Set false right before this Item is torn down (plugin reload). XHR
  // callbacks and timers fire asynchronously and can outlive the QML object
  // that scheduled them, so every deferred callback checks this first
  // instead of touching a possibly-destroyed `root`.
  property bool alive: true

  Component.onDestruction: {
    alive = false
    reconnectTimer.stop()
    backoffTimer.stop()
    if (activePollXhr) {
      // ponytail: abort() drops our reference but doesn't necessarily close
      // the underlying long-poll socket right away (XHR connection reuse);
      // harmless since it's capped by the 6-connections-per-host limit and
      // self-heals once the daemon's own 30s /state timeout hits. Revisit
      // only if reload-heavy sessions show that limit being hit in practice.
      try { activePollXhr.abort() } catch (_) {}
      activePollXhr = null
    }
  }

  readonly property var selfDevice: state && state.self ? state.self : null
  readonly property var devices: state && state.devices ? state.devices : []
  readonly property var pairedDevices: {
    var list = []
    for (var i = 0; i < devices.length; i++) {
      if (devices[i].trustStatus === "trusted") list.push(devices[i])
    }
    return list
  }
  readonly property var nearbyDevices: {
    var list = []
    for (var i = 0; i < devices.length; i++) {
      if (devices[i].trustStatus !== "trusted") list.push(devices[i])
    }
    return list
  }
  readonly property var pairingDialog: state && state.pairingDialog ? state.pairingDialog : null
  readonly property var incomingRequests: state && state.incoming ? state.incoming : []
  readonly property var transfers: state && state.transfers ? state.transfers : []
  readonly property var notifications: state && state.notifications ? state.notifications : []
  readonly property var updateState: state && state.update ? state.update : null
  readonly property bool hasPendingBadge: incomingRequests.length > 0 || (!!pairingDialog && !pairingDialog.isError)
  readonly property bool backgroundDiscoveryEnabled: state && state.settings && state.settings.backgroundDiscoveryEnabled !== undefined
    ? state.settings.backgroundDiscoveryEnabled
    : true

  signal stateUpdated()

  FileView {
    id: controlFile
    path: root.controlPath
    watchChanges: true
    printErrors: false
    onFileChanged: reload()
    onLoaded: root.parseControl(text())
    onLoadFailed: root.handleControlFileMissing()
  }

  Timer {
    id: reconnectTimer
    interval: 3000
    running: !root.daemonRunning
    repeat: true
    onTriggered: controlFile.reload()
  }

  Timer {
    id: backoffTimer
    repeat: false
    onTriggered: if (root.alive) root.pollState(root.stateVersion)
  }

  Process {
    id: startDaemonProc
    command: ["systemctl", "--user", "start", "klardrop"]
    onExited: {
      controlFile.reload()
    }
  }

  function startDaemon() {
    startDaemonProc.running = true
  }

  function handleControlFileMissing() {
    if (activePollXhr) {
      try { activePollXhr.abort() } catch (_) {}
      activePollXhr = null
    }
    daemonRunning = false
    controlPort = 0
    controlToken = ""
    state = null
    stateVersion = 0
  }

  function parseControl(content) {
    try {
      var raw = String(content || "").trim()
      if (raw.length === 0) {
        handleControlFileMissing()
        return
      }
      var obj = JSON.parse(raw)
      if (obj && obj.port && obj.token) {
        var newPort = Number(obj.port)
        var newToken = String(obj.token)
        if (newPort !== controlPort || newToken !== controlToken) {
          controlPort = newPort
          controlToken = newToken
          daemonRunning = true
          backoffMs = 1000
          pollState(0)
        }
      } else {
        handleControlFileMissing()
      }
    } catch (e) {
      handleControlFileMissing()
    }
  }

  function request(method, path, body, callback) {
    if (!daemonRunning || controlPort <= 0) {
      var err = new Error("Klardrop daemon is not running")
      if (callback) callback(err, null)
      return
    }
    var url = "http://127.0.0.1:" + controlPort + path
    var xhr = new XMLHttpRequest()
    xhr.open(method, url)
    xhr.setRequestHeader("Authorization", "Bearer " + controlToken)
    if (body !== null && body !== undefined) {
      xhr.setRequestHeader("Content-Type", "application/json")
    }
    xhr.onreadystatechange = function() {
      if (!root || !root.alive) return
      if (xhr.readyState === XMLHttpRequest.DONE) {
        if (xhr.status >= 200 && xhr.status < 300) {
          var resp = null
          try {
            resp = JSON.parse(xhr.responseText)
          } catch (_) {
            resp = xhr.responseText
          }
          if (callback) callback(null, resp)
        } else {
          var errorObj = new Error("HTTP " + xhr.status + ": " + xhr.responseText)
          errorObj.status = xhr.status
          if (callback) callback(errorObj, null)
        }
      }
    }
    var payload = null
    if (body !== null && body !== undefined) {
      payload = typeof body === "string" ? body : JSON.stringify(body)
    }
    xhr.send(payload)
  }

  function pollState(since) {
    if (!alive || !daemonRunning || controlPort <= 0) return
    if (activePollXhr) {
      // ponytail: same as Component.onDestruction — abort() doesn't
      // guarantee the socket closes immediately, but the 6-per-host limit
      // plus the daemon's 30s /state timeout make that self-healing.
      try { activePollXhr.abort() } catch (_) {}
      activePollXhr = null
    }

    var url = "http://127.0.0.1:" + controlPort + "/state" + (since > 0 ? ("?since=" + since) : "")
    var xhr = new XMLHttpRequest()
    activePollXhr = xhr
    xhr.open("GET", url)
    xhr.setRequestHeader("Authorization", "Bearer " + controlToken)
    xhr.timeout = 35000

    var pollHandled = false
    function handlePollFailure(reason) {
      if (pollHandled) return
      if (!root || !root.alive) return
      pollHandled = true
      if (activePollXhr === xhr) activePollXhr = null
      if (!root.daemonRunning) return
      console.warn("Klardrop", "State poll failed (" + reason + "), backing off " + root.backoffMs + "ms")
      controlFile.reload()
      backoffTimer.interval = root.backoffMs
      root.backoffMs = Math.min(root.backoffMs * 2, 16000)
      backoffTimer.restart()
    }

    xhr.onreadystatechange = function() {
      if (!root || !root.alive) return
      if (xhr.readyState === XMLHttpRequest.DONE) {
        if (activePollXhr === xhr) activePollXhr = null
        if (xhr.status === 200) {
          pollHandled = true
          root.backoffMs = 1000
          try {
            var parsed = JSON.parse(xhr.responseText)
            if (parsed && parsed.ok) {
              root.state = parsed
              if (typeof parsed.version === "number") {
                root.stateVersion = parsed.version
              }
              root.stateUpdated()
            }
          } catch (e) {
            console.warn("Klardrop", "Failed to parse state JSON:", e)
          }
          if (root.daemonRunning) {
            root.pollState(root.stateVersion)
          }
        } else {
          handlePollFailure("HTTP " + xhr.status)
        }
      }
    }
    xhr.ontimeout = function() {
      handlePollFailure("timeout")
    }
    xhr.onerror = function() {
      handlePollFailure("error")
    }
    xhr.send()
  }

  function pair(deviceId, callback) {
    request("POST", "/pair", { deviceId: deviceId }, callback)
  }

  function unpair(deviceId, callback) {
    request("POST", "/unpair", { deviceId: deviceId }, callback)
  }

  function acceptPair(deviceId, callback) {
    request("POST", "/accept-pair", { deviceId: deviceId }, callback)
  }

  function rejectPair(deviceId, callback) {
    request("POST", "/reject-pair", { deviceId: deviceId }, callback)
  }

  function acceptIncoming(receiveId, deviceId, callback) {
    var payload = {}
    if (receiveId !== null && receiveId !== undefined) payload.receiveId = receiveId
    else if (deviceId) payload.deviceId = deviceId
    request("POST", "/accept-incoming", payload, callback)
  }

  function rejectIncoming(receiveId, callback) {
    request("POST", "/reject-incoming", { receiveId: receiveId }, callback)
  }

  function sendText(deviceId, text, callback) {
    request("POST", "/send-text", { deviceId: deviceId, text: text }, callback)
  }

  function sendClipboard(deviceId, callback) {
    request("POST", "/send-clipboard", { deviceId: deviceId }, callback)
  }

  function sendFile(deviceId, paths, callback) {
    request("POST", "/send-file", { deviceId: deviceId, paths: paths }, callback)
  }

  function renameDevice(name, callback) {
    request("POST", "/rename-device", { name: name }, callback)
  }

  function setSettings(settingsObj, callback) {
    request("POST", "/settings", settingsObj, callback)
  }

  // `before` (a message id cursor from a previous response's `nextBefore`)
  // fetches the page just older than it; pass null/undefined for the
  // newest page.
  function getHistory(deviceId, before, callback) {
    var path = "/history?device=" + encodeURIComponent(deviceId)
    if (before !== null && before !== undefined) path += "&before=" + encodeURIComponent(before)
    request("GET", path, null, callback)
  }

  function markHistoryRead(deviceId, callback) {
    request("POST", "/history/read", { deviceId: deviceId }, callback)
  }

  // `deviceId` empty/null clears the active chat (e.g. on deselect/close).
  function setActiveChat(deviceId, callback) {
    request("POST", "/active-chat", { deviceId: deviceId || null }, callback)
  }

  function retry(fileTransferId, callback) {
    request("POST", "/retry", { fileTransferId: fileTransferId }, callback)
  }

  function checkUpdate(callback) {
    request("POST", "/update/check", null, callback)
  }

  // `force`: retry after a 409 (active transfers in progress) once the user
  // has confirmed they still want to restart.
  function applyUpdate(force, callback) {
    request("POST", "/update/apply", force ? { force: true } : null, callback)
  }

  function reportProblem(description, email, callback) {
    var payload = { description: description }
    if (email) payload.email = email
    request("POST", "/report-problem", payload, callback)
  }

  function dismissNotification(id, callback) {
    request("POST", "/notification/dismiss", { id: id }, callback)
  }

  function pairFromNotification(id, callback) {
    request("POST", "/notification/pair", { id: id }, callback)
  }

  function dismissIncoming(receiveId, callback) {
    request("POST", "/incoming/dismiss", { receiveId: receiveId }, callback)
  }

  function openIncoming(receiveId, callback) {
    request("POST", "/incoming/open", { receiveId: receiveId }, callback)
  }

  function dismissPairingError(callback) {
    request("POST", "/pairing-dialog/dismiss", null, callback)
  }

  // Drag & drop support: QML hands a DropArea's `drop.urls` as an array of
  // QUrl-like objects. Only `file://` URLs make sense as send targets — a
  // dragged browser link or text selection stringifies to something else
  // and is silently dropped rather than sent as a bogus "path".
  function fileUrlToPath(url) {
    var s = String(url || "")
    if (s.indexOf("file://") !== 0) return null
    var path = s.substring("file://".length)
    try {
      return decodeURIComponent(path)
    } catch (e) {
      return null
    }
  }

  function dropUrlsToPaths(urls) {
    var paths = []
    var list = urls || []
    for (var i = 0; i < list.length; i++) {
      var p = root.fileUrlToPath(list[i])
      if (p) paths.push(p)
    }
    return paths
  }

  // --- Shared display/process helpers -------------------------------
  // Kept here (rather than duplicated per-surface) so the panel and the
  // future standalone window render identically.

  function formatBytes(bytes) {
    var n = Number(bytes)
    if (!isFinite(n) || n <= 0) return "0 B"
    if (n < 1024) return n + " B"
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB"
    if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + " MB"
    return (n / (1024 * 1024 * 1024)).toFixed(2) + " GB"
  }

  function deviceGlyph(type) {
    // Real values from DebugControl are the DeviceType enum names
    // (common/src/commonMain/.../utils/DeviceType.kt: MOBILE, DESKTOP,
    // UNKNOWN) — not "phone"/"desktop" — so match those, case-insensitively.
    var t = String(type || "").toUpperCase()
    if (t === "DESKTOP") return "󰌢"
    if (t === "MOBILE") return "󰄜"
    return "󰒋"
  }

  Process {
    id: fileSelectProc
    command: ["omarchy-file-select", "--multiple"]
    property string targetDeviceId: ""
    property var doneCallback: null
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        var cb = fileSelectProc.doneCallback
        var target = fileSelectProc.targetDeviceId
        fileSelectProc.doneCallback = null
        fileSelectProc.targetDeviceId = ""
        var textData = String(text || "").trim()
        if (!textData || !target) return
        var lines = textData.split("\n")
        var paths = []
        for (var i = 0; i < lines.length; i++) {
          var p = lines[i].trim()
          if (p) paths.push(p)
        }
        if (paths.length > 0) root.sendFile(target, paths, cb)
      }
    }
  }

  // Runs the file picker and, on selection, sends the picked paths to
  // `deviceId`. `callback` (optional) is the sendFile callback.
  function selectAndSendFiles(deviceId, callback) {
    if (!deviceId) return
    if (fileSelectProc.running) return
    fileSelectProc.targetDeviceId = deviceId
    fileSelectProc.doneCallback = callback || null
    fileSelectProc.running = true
  }

  Process {
    id: openFileProc
  }

  function openFile(path) {
    if (!path) return
    openFileProc.command = ["xdg-open", path]
    openFileProc.running = true
  }

  Process {
    id: copyTextProc
  }

  function copyText(str) {
    if (!str) return
    copyTextProc.command = ["wl-copy", "--", str]
    copyTextProc.running = true
  }
}
