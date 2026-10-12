import QtQuick
import QtQuick.Controls
import QtQuick.Window
import Quickshell
import qs.Commons
import qs.Ui

// Standalone Klardrop window (Panel.qml's "openWindow" IPC target). Owns no
// state of its own beyond what's needed to drive its two-pane layout — all
// daemon state/polling comes from `service` (the same Main instance the bar
// panel uses; Panel.qml instantiates this lazily via a Loader so there is
// still only one Main/poll loop per plugin instance).
FloatingWindow {
  id: window

  property var service: null
  property color foreground: Color.foreground
  property color accent: Color.accent
  property color urgent: Color.urgent
  property color dim: Qt.darker(foreground, 1.4)
  property color surface: Color.popups.background
  property string fontFamily: Style.font.family
  property int initialWidth: 1000
  property int initialHeight: 700

  // Emitted (debounced) while the user resizes, so Panel.qml can persist the
  // new size. Not read back here — Panel.qml re-seeds initialWidth/Height
  // the next time it loads this component.
  signal sizeResolved(int w, int h)

  // Fully opaque regardless of what alpha the theme's popup surface carries —
  // this window sits over the desktop, not inside a compositor blur stack,
  // so a translucent `surface` would let whatever's behind show through.
  readonly property color opaqueSurface: Qt.rgba(window.surface.r, window.surface.g, window.surface.b, 1)

  title: "Klardrop"
  color: window.opaqueSurface
  implicitWidth: window.initialWidth
  implicitHeight: window.initialHeight
  minimumSize: Qt.size(760, 480)

  onWidthChanged: sizeDebounce.restart()
  onHeightChanged: sizeDebounce.restart()

  Timer {
    id: sizeDebounce
    interval: 800
    onTriggered: window.sizeResolved(window.width, window.height)
  }

  Timer {
    id: reportCloseTimer
    interval: 1200
    repeat: false
    onTriggered: window.closeReportProblem()
  }

  property string selectedDeviceId: ""
  property string selectedDeviceName: ""
  // Oldest-first (server pages are newest-first; every page is reversed on
  // arrival so the Repeater below can render top-to-bottom like a normal
  // chat log).
  // ponytail: not trimmed — a device chatted with for a very long time and
  // scrolled all the way back keeps every loaded page in memory for the
  // life of the window. Add an eviction of the oldest pages once this is
  // observed to matter in practice.
  property var historyMessages: []
  property bool loadingHistory: false
  // Guards a second /history request for an older page while one is
  // in flight, and doubles as "is there anything worth showing a spinner
  // for" at the top of the flickable.
  property bool loadingOlder: false
  // Cursor for the next older page (`nextBefore` from the oldest page
  // loaded so far); null once the server says there's nothing older.
  property var nextBeforeId: null
  // Bumped on every syncLatestMessages() call so a response from a
  // superseded request (an earlier /state bump's /history round-trip that
  // is slow to come back) can be told apart from the latest one and
  // dropped instead of clobbering newer data out of order.
  property int historySyncSeq: 0
  property bool renamePromptOpen: false
  property var forgetTargetDevice: null
  property bool longTextViewerOpen: false
  property string longTextViewerContent: ""
  // messageId -> "pending" | <thumbnail path string> | null (fetched, no
  // thumbnail — ffmpeg missing or the source file is gone). Lives on the
  // window (not a per-delegate property) because Repeater rebuilds every
  // delegate whenever `historyMessages` gets a new array identity, which
  // happens on every merge below; keying the fetch state here is what
  // makes "at most once per message" hold across those rebuilds.
  property var thumbnails: ({})

  readonly property var selectedTransfers: {
    var list = []
    var all = window.service ? window.service.transfers : []
    for (var i = 0; i < all.length; i++) {
      if (all[i].deviceId === window.selectedDeviceId) list.push(all[i])
    }
    return list
  }

  // Sync (not reload) the open chat whenever a poll brings new state — only
  // wired up while this window (and thus this Connections) exists. If the
  // selected device fell out of the trusted list (unpaired/forgotten
  // elsewhere, e.g. from the bar panel) drop the selection instead of
  // endlessly syncing a chat for a device that's gone.
  Connections {
    target: window.service
    function onStateUpdated() {
      if (window.selectedDeviceId === "") return
      var stillPaired = false
      var paired = window.service.pairedDevices
      for (var i = 0; i < paired.length; i++) {
        if (paired[i].deviceId === window.selectedDeviceId) { stillPaired = true; break }
      }
      if (!stillPaired) {
        window.clearSelection()
      } else {
        window.syncLatestMessages()
      }
    }
  }

  function clearSelection() {
    if (window.selectedDeviceId !== "" && window.service) window.service.setActiveChat("", null)
    window.selectedDeviceId = ""
    window.selectedDeviceName = ""
    window.historyMessages = []
    window.nextBeforeId = null
    window.loadingHistory = false
    window.loadingOlder = false
    window.thumbnails = ({})
  }

  function selectDevice(deviceId, deviceName) {
    if (window.selectedDeviceId !== "" && window.selectedDeviceId !== deviceId && window.service) {
      window.service.setActiveChat("", null)
    }
    window.selectedDeviceId = deviceId
    window.selectedDeviceName = deviceName || deviceId
    window.historyMessages = []
    window.nextBeforeId = null
    window.loadHistory(deviceId)
    if (window.service) {
      window.service.setActiveChat(deviceId, null)
      if (contentRoot.windowActive) window.service.markHistoryRead(deviceId, null)
    }
  }

  // Full reset to the newest page — used on select and the header's
  // refresh button.
  function loadHistory(deviceId) {
    if (!deviceId || !window.service) return
    window.loadingHistory = true
    window.service.getHistory(deviceId, null, function(err, resp) {
      if (!window || window.selectedDeviceId !== deviceId) return
      window.loadingHistory = false
      if (!err && resp && resp.messages) {
        var page = resp.messages.slice().reverse()
        window.historyMessages = page
        window.nextBeforeId = (resp.nextBefore === undefined) ? null : resp.nextBefore
        window.pruneThumbnails(page)
      }
    })
  }

  // Fetches the page older than whatever's currently loaded and prepends
  // it, keeping the flickable's visual scroll position stable.
  function loadOlderHistory() {
    if (!window.selectedDeviceId || !window.service) return
    if (window.loadingOlder || window.nextBeforeId === null) return
    window.loadingOlder = true
    // Tell historyFlick's auto-scroll heuristic to stand down while this
    // page lands — the contentY correction below is what keeps the visual
    // position stable, and the two must not both act on the same resize.
    historyFlick.skipAutoScroll = true
    var deviceId = window.selectedDeviceId
    var beforeId = window.nextBeforeId
    var prevHeight = historyColumn.implicitHeight
    window.service.getHistory(deviceId, beforeId, function(err, resp) {
      if (!window || window.selectedDeviceId !== deviceId) {
        window.loadingOlder = false
        historyFlick.skipAutoScroll = false
        return
      }
      window.loadingOlder = false
      if (err || !resp || !resp.messages) {
        historyFlick.skipAutoScroll = false
        return
      }
      var older = resp.messages.slice().reverse()
      window.historyMessages = older.concat(window.historyMessages)
      window.nextBeforeId = (resp.nextBefore === undefined) ? null : resp.nextBefore
      Qt.callLater(function() {
        if (!window) { historyFlick.skipAutoScroll = false; return }
        var delta = historyColumn.implicitHeight - prevHeight
        if (delta > 0) historyFlick.contentY += delta
        historyFlick.lastContentHeight = historyFlick.contentHeight
        historyFlick.skipAutoScroll = false
      })
    })
  }

  // Refreshes just the newest page and merges it into whatever's already
  // loaded — updates in-place fields (delivery status, file transfer
  // status) on known messages and appends genuinely new ones, without
  // discarding older pages the user has scrolled back into.
  function syncLatestMessages() {
    if (!window.selectedDeviceId || !window.service) return
    var deviceId = window.selectedDeviceId
    // Each /state bump can trigger its own sync before the previous one's
    // /history round-trip lands (a slow request, a burst of state
    // updates). Only the response matching the latest request for this
    // device is allowed to apply — an older one applying afterwards could
    // clobber newer data (e.g. re-show a message as SENDING after it's
    // already SENT).
    var mySeq = ++window.historySyncSeq
    window.service.getHistory(deviceId, null, function(err, resp) {
      if (!window || window.selectedDeviceId !== deviceId) return
      if (window.historySyncSeq !== mySeq) return
      if (err || !resp || !resp.messages) return
      var page = resp.messages.slice().reverse()
      var byId = {}
      for (var i = 0; i < window.historyMessages.length; i++) byId[window.historyMessages[i].id] = i
      var merged = window.historyMessages.slice()
      var addedNew = false
      for (var j = 0; j < page.length; j++) {
        var m = page[j]
        if (m.id in byId) merged[byId[m.id]] = m
        else { merged.push(m); addedNew = true }
      }
      window.historyMessages = merged
      window.pruneThumbnails(merged)
      if (addedNew && contentRoot.windowActive) window.service.markHistoryRead(deviceId, null)
    })
  }

  // Drops cached thumbnail state for messages that are no longer loaded —
  // otherwise `thumbnails` only ever grows for the life of the window.
  function pruneThumbnails(messages) {
    var seen = {}
    for (var i = 0; i < messages.length; i++) seen[messages[i].id] = true
    var kept = {}
    var changed = false
    for (var k in window.thumbnails) {
      if (k in seen) kept[k] = window.thumbnails[k]
      else changed = true
    }
    if (changed) window.thumbnails = kept
  }

  function requestThumbnail(messageId) {
    if (messageId === undefined || messageId === null || !window.service) return
    if (messageId in window.thumbnails) return
    var pending = {}
    for (var k in window.thumbnails) pending[k] = window.thumbnails[k]
    pending[messageId] = "pending"
    window.thumbnails = pending
    window.service.request("GET", "/thumbnail?id=" + encodeURIComponent(messageId), null, function(err, resp) {
      if (!window) return
      var result = (!err && resp && resp.ok) ? (resp.thumbnail || null) : null
      var updated = {}
      for (var k2 in window.thumbnails) updated[k2] = window.thumbnails[k2]
      updated[messageId] = result
      window.thumbnails = updated
    })
  }

  function showLongText(text) {
    window.longTextViewerContent = text || ""
    window.longTextViewerOpen = true
  }

  function parentDirOf(path) {
    var p = String(path || "")
    var idx = p.lastIndexOf("/")
    return idx > 0 ? p.substring(0, idx) : "/"
  }

  function dayKey(ts) {
    var d = new Date(Number(ts))
    return d.getFullYear() + "-" + d.getMonth() + "-" + d.getDate()
  }

  function formatChatDay(ts) {
    var d = new Date(Number(ts))
    var now = new Date()
    var startOfDay = function(x) { return new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime() }
    var diffDays = Math.round((startOfDay(now) - startOfDay(d)) / 86400000)
    if (diffDays === 0) return "Today"
    if (diffDays === 1) return "Yesterday"
    return d.toLocaleDateString(Qt.locale(), "MMM d, yyyy")
  }

  function formatChatTime(ts) {
    return Qt.formatTime(new Date(Number(ts)), "hh:mm")
  }

  function sendFiles(deviceId) {
    if (!deviceId || !window.service) return
    window.service.selectAndSendFiles(deviceId, function(err, resp) {
      if (!window) return
      if (window.selectedDeviceId === deviceId) window.syncLatestMessages()
    })
  }

  // Drag & drop onto a device row or the chat pane: `urls` is the raw
  // `drop.urls` array (file:// QUrls), converted via Main's helper.
  function sendDroppedFiles(deviceId, urls) {
    if (!deviceId || !window.service) return
    var paths = window.service.dropUrlsToPaths(urls)
    if (paths.length === 0) return
    window.service.sendFile(deviceId, paths, function(err, resp) {
      if (!window) return
      if (window.selectedDeviceId === deviceId) window.syncLatestMessages()
    })
  }

  function sendComposerText() {
    var txt = composerField.text.trim()
    if (!txt || !window.selectedDeviceId) return
    var targetId = window.selectedDeviceId
    composerField.text = ""
    window.service.sendText(targetId, txt, function(err, resp) {
      if (!window) return
      if (window.selectedDeviceId === targetId) window.syncLatestMessages()
    })
  }

  function promptRename() {
    window.renamePromptOpen = true
    renameOverlay.text = window.service && window.service.selfDevice ? window.service.selfDevice.deviceName : ""
  }

  function submitRename(text) {
    var txt = String(text || "").trim()
    if (txt && window.service) window.service.renameDevice(txt, null)
    window.renamePromptOpen = false
  }

  function cancelRename() {
    window.renamePromptOpen = false
  }

  // "Restart to update": a plain apply, unless the daemon says 409 (active
  // transfers in progress) — then confirm with the user before forcing it
  // through with `{"force":true}`.
  function applyUpdate(force) {
    window.service.applyUpdate(force, function(err, resp) {
      if (!window) return
      if (err && err.status === 409 && !force) {
        window.applyForceConfirmOpen = true
      }
    })
  }

  property bool applyForceConfirmOpen: false

  property bool reportProblemOpen: false
  property string reportProblemOutcome: ""

  function closeReportProblem() {
    reportCloseTimer.stop()
    window.reportProblemOpen = false
    window.reportProblemOutcome = ""
    reportDescriptionField.text = ""
    reportEmailField.text = ""
  }

  function submitReportProblem(description, email) {
    var desc = String(description || "").trim()
    if (!desc || !window.service) return
    window.service.reportProblem(desc, String(email || "").trim(), function(err, resp) {
      if (!window) return
      var outcome = (!err && resp) ? resp.outcome : null
      if (outcome === "sent") {
        window.reportProblemOutcome = "Thanks — report sent."
        reportCloseTimer.restart()
      } else if (outcome === "disabled") {
        window.reportProblemOutcome = "Reporting is turned off in this build, so nothing was sent."
      } else {
        window.reportProblemOutcome = "Could not send the report. Please try again later."
      }
    })
  }

  Item {
    id: contentRoot
    anchors.fill: parent

    // Backstop for the window's own `color`: makes sure nothing behind the
    // window (other windows, desktop) can ever show through this content
    // tree, independent of how FloatingWindow composites its background.
    readonly property bool windowActive: Window.active

    Rectangle {
      anchors.fill: parent
      color: window.opaqueSurface
    }

    // Offline state
    Column {
      anchors.centerIn: parent
      visible: !(window.service && window.service.daemonRunning)
      width: Math.min(parent.width - Style.space(64), Style.space(360))
      spacing: Style.spacing.sm

      Text {
        width: parent.width
        text: "Klardrop daemon is not running."
        color: window.dim
        font.family: window.fontFamily
        font.pixelSize: Style.font.body
        wrapMode: Text.WordWrap
        horizontalAlignment: Text.AlignHCenter
      }

      Button {
        anchors.horizontalCenter: parent.horizontalCenter
        text: "Start Klardrop"
        iconText: "󰐥"
        bordered: true
        selected: true
        fontFamily: window.fontFamily
        onClicked: window.service.startDaemon()
      }
    }

    // Online state
    Item {
      anchors.fill: parent
      visible: !!(window.service && window.service.daemonRunning)

      Column {
        id: bannerStack
        anchors {
          left: parent.left
          right: parent.right
          top: parent.top
          margins: Style.spacing.sm
        }
        spacing: Style.spacing.xs
        visible: window.service && (
          window.service.incomingRequests.length > 0
          || window.service.notifications.length > 0
          || !!(window.service.pairingDialog && window.service.pairingDialog.isError)
          || updateBanner.visible)

        UpdateBanner {
          id: updateBanner
          width: parent.width
          update: window.service ? window.service.updateState : null
          foreground: window.foreground
          accent: window.accent
          urgent: window.urgent
          dim: window.dim
          fontFamily: window.fontFamily
          onRestartRequested: window.applyUpdate(false)
          onCheckRequested: window.service.checkUpdate(null)
          onCopyCommandRequested: function(command) { window.service.copyText(command) }
          onOpenUrlRequested: function(url) { if (/^https?:\/\//i.test(url)) window.service.openFile(url) }
        }

        NotificationRow {
          visible: !!(window.service && window.service.pairingDialog && window.service.pairingDialog.isError)
          width: parent.width
          showPairAction: false
          message: window.service && window.service.pairingDialog
            ? (window.service.pairingDialog.errorMessage
              || ("Pairing with " + (window.service.pairingDialog.deviceName || window.service.pairingDialog.deviceId) + " failed."))
            : ""
          foreground: window.foreground
          accent: window.accent
          urgent: window.urgent
          fontFamily: window.fontFamily
          onDismissRequested: window.service.dismissPairingError(null)
        }

        Repeater {
          model: window.service ? window.service.notifications : []

          NotificationRow {
            required property var modelData
            width: parent.width
            deviceId: modelData.deviceId || ""
            deviceName: modelData.deviceName || ""
            message: (modelData.deviceName || modelData.deviceId || "A device") + " no longer trusts this device"
            foreground: window.foreground
            accent: window.accent
            urgent: window.urgent
            fontFamily: window.fontFamily
            onDismissRequested: window.service.dismissNotification(modelData.id, null)
            onPairRequested: window.service.pairFromNotification(modelData.id, null)
          }
        }

        Repeater {
          model: window.service ? window.service.incomingRequests : []

          IncomingRequestRow {
            required property var modelData
            width: parent.width
            deviceId: modelData.deviceId || ""
            deviceName: modelData.deviceName || ""
            status: modelData.status || ""
            pendingAuth: !!modelData.pendingAuth
            fileCount: modelData.fileCount || 0
            fileNames: modelData.fileNames || []
            totalSizeLabel: window.service.formatBytes(modelData.totalSize)
            textPreview: modelData.text || ""
            foreground: window.foreground
            accent: window.accent
            urgent: window.urgent
            fontFamily: window.fontFamily
            onAcceptRequested: window.service.acceptIncoming(modelData.receiveId, modelData.deviceId, null)
            onRejectRequested: window.service.rejectIncoming(modelData.receiveId, null)
            onOpenRequested: window.service.openIncoming(modelData.receiveId, null)
            onDismissRequested: window.service.dismissIncoming(modelData.receiveId, null)
          }
        }
      }

      Item {
        id: panes
        anchors {
          left: parent.left
          right: parent.right
          bottom: parent.bottom
          top: bannerStack.visible ? bannerStack.bottom : parent.top
          topMargin: bannerStack.visible ? Style.spacing.sm : 0
        }

        // LEFT PANE ---------------------------------------------------
        Item {
          id: leftPane
          width: Style.space(300)
          anchors { left: parent.left; top: parent.top; bottom: parent.bottom }

          Rectangle {
            anchors { right: parent.right; top: parent.top; bottom: parent.bottom }
            width: 1
            color: Qt.rgba(window.foreground.r, window.foreground.g, window.foreground.b, 0.12)
          }

          Flickable {
            anchors.fill: parent
            anchors.margins: Style.spacing.md
            anchors.rightMargin: Style.spacing.md + 1
            contentWidth: width
            contentHeight: leftColumn.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            Column {
              id: leftColumn
              width: parent.width
              spacing: Style.spacing.md

              PanelHero {
                width: parent.width
                title: window.service && window.service.selfDevice ? window.service.selfDevice.deviceName : "Klardrop"
                meta: window.service && window.service.selfDevice
                  ? ("This device · ID: " + window.service.selfDevice.deviceId)
                  : "This device"
                fontFamily: window.fontFamily
                foreground: window.foreground
                iconComponent: Component {
                  Text {
                    text: "󰅟"
                    font.family: window.fontFamily
                    font.pixelSize: Style.font.display
                    color: window.foreground
                  }
                }
                trailingControl: discoveryToggleComp
              }

              Component {
                id: discoveryToggleComp
                Row {
                  id: discoveryRow
                  spacing: Style.spacing.xs
                  property bool hot: false

                  Text {
                    text: "Discovery"
                    anchors.verticalCenter: parent.verticalCenter
                    color: window.foreground
                    font.family: window.fontFamily
                    font.pixelSize: Style.font.caption
                  }

                  ToggleSwitch {
                    anchors.verticalCenter: parent.verticalCenter
                    checked: window.service ? window.service.backgroundDiscoveryEnabled : true
                    foreground: window.foreground
                    accent: window.accent
                    onToggled: window.service.setSettings({ backgroundDiscovery: !checked })
                    onHovered: function(isHovered) { discoveryRow.hot = isHovered }
                  }

                  PanelToolTip {
                    visible: discoveryRow.hot
                    fontFamily: window.fontFamily
                    text: (window.service && window.service.backgroundDiscoveryEnabled)
                      ? "Background discovery is on — click to pause"
                      : "Background discovery is off — click to enable"
                  }
                }
              }

              PromptOverlay {
                id: renameOverlay
                width: parent.width
                opened: window.renamePromptOpen
                label: "Rename device:"
                fontFamily: window.fontFamily
                foreground: window.foreground
                accent: window.accent
                onSubmitted: function(text) { window.submitRename(text) }
                onCanceled: window.cancelRename()
              }

              Row {
                width: parent.width
                visible: !window.renamePromptOpen

                Button {
                  text: "Rename this device"
                  iconText: "󰏫"
                  fontFamily: window.fontFamily
                  fontSize: Style.font.caption
                  onClicked: window.promptRename()
                }
              }

              PanelSeparator { foreground: window.foreground }

              PanelSectionHeader {
                text: "TRUSTED (" + (window.service ? window.service.pairedDevices.length : 0) + ")"
                fontFamily: window.fontFamily
                foreground: window.foreground
              }

              Text {
                visible: !window.service || window.service.pairedDevices.length === 0
                text: "No paired devices yet."
                color: window.dim
                font.family: window.fontFamily
                font.pixelSize: Style.font.caption
              }

              Repeater {
                model: window.service ? window.service.pairedDevices : []

                Item {
                  id: trustedRowWrap
                  required property var modelData
                  width: parent.width
                  height: deviceRow.height

                  MouseArea {
                    anchors.fill: parent
                    onClicked: window.selectDevice(trustedRowWrap.modelData.deviceId, trustedRowWrap.modelData.deviceName)
                  }

                  DeviceRow {
                    id: deviceRow
                    width: parent.width
                    paired: true
                    selected: window.selectedDeviceId === trustedRowWrap.modelData.deviceId
                    deviceId: trustedRowWrap.modelData.deviceId || ""
                    deviceName: trustedRowWrap.modelData.deviceName || ""
                    glyph: window.service.deviceGlyph(trustedRowWrap.modelData.deviceType)
                    reachability: trustedRowWrap.modelData.reachability || ""
                    unreadCount: trustedRowWrap.modelData.unreadCount || 0
                    foreground: window.foreground
                    accent: window.accent
                    urgent: window.urgent
                    dim: window.dim
                    fontFamily: window.fontFamily
                    onSendFilesRequested: window.sendFiles(trustedRowWrap.modelData.deviceId)
                    onSendClipboardRequested: window.service.sendClipboard(trustedRowWrap.modelData.deviceId, null)
                    onSendMessageRequested: {
                      window.selectDevice(trustedRowWrap.modelData.deviceId, trustedRowWrap.modelData.deviceName)
                      composerField.forceActiveFocus()
                    }
                    onForgetRequested: window.forgetTargetDevice = trustedRowWrap.modelData
                    onFilesDropped: function(urls) {
                      window.sendDroppedFiles(trustedRowWrap.modelData.deviceId, urls)
                    }
                  }
                }
              }

              PanelSeparator { foreground: window.foreground }

              PanelSectionHeader {
                text: "NEARBY (" + (window.service ? window.service.nearbyDevices.length : 0) + ")"
                fontFamily: window.fontFamily
                foreground: window.foreground
              }

              Text {
                visible: !window.service || window.service.nearbyDevices.length === 0
                text: "Scanning for nearby devices..."
                color: window.dim
                font.family: window.fontFamily
                font.pixelSize: Style.font.caption
              }

              Repeater {
                model: window.service ? window.service.nearbyDevices : []

                DeviceRow {
                  required property var modelData
                  width: parent.width
                  paired: false
                  deviceId: modelData.deviceId || ""
                  deviceName: modelData.deviceName || ""
                  glyph: window.service.deviceGlyph(modelData.deviceType)
                  reachability: modelData.reachability || ""
                  foreground: window.foreground
                  accent: window.accent
                  urgent: window.urgent
                  dim: window.dim
                  fontFamily: window.fontFamily
                  onPairRequested: window.service.pair(modelData.deviceId, null)
                }
              }

              PanelSeparator { foreground: window.foreground }

              PanelSectionHeader {
                text: "SETTINGS"
                fontFamily: window.fontFamily
                foreground: window.foreground
              }

              Row {
                width: parent.width
                spacing: Style.spacing.xs

                Text {
                  width: parent.width - checkUpdateBtn.width - Style.spacing.xs
                  textFormat: Text.PlainText
                  text: {
                    var u = window.service ? window.service.updateState : null
                    if (!u) return "Version —"
                    var v = "Version " + (u.currentVersion || "—")
                    if (u.channel && u.channel !== "stable") v += " · " + u.channel
                    if (u.status === "checking") v += " · checking…"
                    else if (u.status === "up_to_date") v += " · up to date"
                    return v
                  }
                  color: window.dim
                  font.family: window.fontFamily
                  font.pixelSize: Style.font.caption
                  wrapMode: Text.WordWrap
                }

                Button {
                  id: checkUpdateBtn
                  text: (window.service && window.service.updateState && window.service.updateState.status === "checking")
                    ? "Checking…"
                    : "Check for updates"
                  fontFamily: window.fontFamily
                  fontSize: Style.font.caption
                  enabled: !(window.service && window.service.updateState && window.service.updateState.status === "checking")
                  onClicked: window.service.checkUpdate(null)
                }
              }

              Column {
                width: parent.width
                spacing: Style.spacing.xs
                visible: !window.reportProblemOpen

                Text {
                  textFormat: Text.PlainText
                  text: "Report a problem"
                  color: window.accent
                  font.family: window.fontFamily
                  font.pixelSize: Style.font.caption
                  font.underline: true

                  MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: window.reportProblemOpen = true
                  }
                }
              }

              Column {
                width: parent.width
                spacing: Style.spacing.xs
                visible: window.reportProblemOpen

                Text {
                  width: parent.width
                  textFormat: Text.PlainText
                  text: "Describe what happened. The recent activity log is attached automatically."
                  color: window.dim
                  font.family: window.fontFamily
                  font.pixelSize: Style.font.caption
                  wrapMode: Text.WordWrap
                }

                TextArea {
                  id: reportDescriptionField
                  width: parent.width
                  wrapMode: TextArea.Wrap
                  placeholderText: "What went wrong?"
                  color: window.foreground
                  font.family: window.fontFamily
                  font.pixelSize: Style.font.caption
                  background: Rectangle {
                    color: "transparent"
                    border.color: Qt.rgba(window.foreground.r, window.foreground.g, window.foreground.b, 0.24)
                    radius: Style.cornerRadius
                  }
                }

                TextField {
                  id: reportEmailField
                  width: parent.width
                  placeholderText: "Email (optional)"
                }

                Text {
                  visible: window.reportProblemOutcome !== ""
                  width: parent.width
                  textFormat: Text.PlainText
                  text: window.reportProblemOutcome
                  color: window.reportProblemOutcome.indexOf("Thanks") === 0 ? window.dim : window.urgent
                  font.family: window.fontFamily
                  font.pixelSize: Style.font.caption
                  wrapMode: Text.WordWrap
                }

                Row {
                  anchors.right: parent.right
                  spacing: Style.spacing.xs

                  Button {
                    text: "Cancel"
                    fontFamily: window.fontFamily
                    fontSize: Style.font.caption
                    onClicked: window.closeReportProblem()
                  }

                  Button {
                    text: "Send"
                    fontFamily: window.fontFamily
                    fontSize: Style.font.caption
                    enabled: reportDescriptionField.text.trim().length > 0
                    opacity: enabled ? 1.0 : 0.5
                    onClicked: window.submitReportProblem(reportDescriptionField.text, reportEmailField.text)
                  }
                }
              }
            }
          }
        }

        // RIGHT PANE --------------------------------------------------
        Item {
          id: rightPane
          anchors { left: leftPane.right; right: parent.right; top: parent.top; bottom: parent.bottom }
          anchors.leftMargin: 1

          Column {
            anchors.centerIn: parent
            visible: window.selectedDeviceId === ""
            spacing: Style.spacing.xs
            width: Math.min(parent.width - Style.space(64), Style.space(320))

            Text {
              width: parent.width
              text: "Select a device"
              horizontalAlignment: Text.AlignHCenter
              color: window.foreground
              font.family: window.fontFamily
              font.pixelSize: Style.font.title
              font.bold: true
            }

            Text {
              width: parent.width
              text: "Choose a trusted device on the left to chat or send files."
              horizontalAlignment: Text.AlignHCenter
              wrapMode: Text.WordWrap
              color: window.dim
              font.family: window.fontFamily
              font.pixelSize: Style.font.bodySmall
            }
          }

          Item {
            id: chatPane
            anchors.fill: parent
            anchors.margins: Style.spacing.md
            visible: window.selectedDeviceId !== ""

            property bool dragHover: false

            DropArea {
              id: chatDropArea
              anchors.fill: parent
              enabled: window.selectedDeviceId !== ""
              keys: ["text/uri-list"]
              onEntered: chatPane.dragHover = true
              onExited: chatPane.dragHover = false
              onDropped: function(drop) {
                chatPane.dragHover = false
                window.sendDroppedFiles(window.selectedDeviceId, drop.urls)
              }
            }

            Rectangle {
              anchors.fill: parent
              visible: chatPane.dragHover
              radius: Style.cornerRadius
              color: Qt.rgba(window.accent.r, window.accent.g, window.accent.b, 0.12)
              border.color: window.accent
              border.width: 2
              z: 10

              Text {
                anchors.centerIn: parent
                textFormat: Text.PlainText
                text: "Drop to send"
                color: window.accent
                font.family: window.fontFamily
                font.pixelSize: Style.font.title
                font.bold: true
              }
            }

            Row {
              id: chatHeader
              anchors { left: parent.left; right: parent.right; top: parent.top }
              spacing: Style.spacing.sm

              Text {
                width: parent.width - Style.space(40)
                text: window.selectedDeviceName
                textFormat: Text.PlainText
                font.family: window.fontFamily
                font.pixelSize: Style.font.title
                font.bold: true
                color: window.foreground
                elide: Text.ElideRight
              }

              PanelActionButton {
                iconText: "󰑐"
                tooltipText: "Refresh"
                fontFamily: window.fontFamily
                onClicked: window.loadHistory(window.selectedDeviceId)
              }
            }

            Column {
              id: transfersCol
              anchors { left: parent.left; right: parent.right; top: chatHeader.bottom; topMargin: Style.spacing.sm }
              spacing: Style.spacing.xs
              visible: window.selectedTransfers.length > 0

              Repeater {
                model: window.selectedTransfers

                TransferRow {
                  required property var modelData
                  width: parent.width
                  isSender: !!modelData.isSender
                  fileName: modelData.fileName || ""
                  totalSize: Number(modelData.totalSize || 0)
                  transferredSize: Number(modelData.transferredSize || 0)
                  sizeLabel: window.service.formatBytes(modelData.transferredSize) + " of " + window.service.formatBytes(modelData.totalSize)
                  foreground: window.foreground
                  accent: window.accent
                  dim: window.dim
                  fontFamily: window.fontFamily
                }
              }
            }

            Flickable {
              id: historyFlick
              anchors {
                left: parent.left
                right: parent.right
                top: transfersCol.visible ? transfersCol.bottom : chatHeader.bottom
                topMargin: Style.spacing.sm
                bottom: composerRow.top
                bottomMargin: Style.spacing.sm
              }
              clip: true
              contentWidth: width
              contentHeight: historyColumn.implicitHeight
              boundsBehavior: Flickable.StopAtBounds
              ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

              // Auto-scroll only for a fresh load or when the user is
              // already reading the bottom — someone scrolled up to read
              // older messages shouldn't get yanked down by a new one.
              // `skipAutoScroll` is set while an older page is being
              // prepended, so this heuristic doesn't fight the explicit
              // contentY correction loadOlderHistory() applies below.
              property real lastContentHeight: 0
              property bool skipAutoScroll: false
              onContentHeightChanged: {
                if (skipAutoScroll) {
                  lastContentHeight = contentHeight
                  return
                }
                var nearBottom = lastContentHeight <= 0 || (contentY + height) >= (lastContentHeight - Style.space(40))
                if (nearBottom) contentY = Math.max(0, contentHeight - height)
                lastContentHeight = contentHeight
              }
              // Fetch the next older page once the user scrolls near the
              // top. loadOlderHistory() itself guards against duplicate
              // in-flight requests and a page count with no more history.
              onContentYChanged: {
                if (contentY <= Style.space(40) && !window.loadingOlder && window.nextBeforeId !== null) {
                  window.loadOlderHistory()
                }
              }

              Column {
                id: historyColumn
                width: parent.width
                spacing: Style.spacing.xs

                Text {
                  visible: window.historyMessages.length === 0
                  text: window.loadingHistory ? "Loading history..." : "No message history with this device."
                  color: window.dim
                  font.family: window.fontFamily
                  font.pixelSize: Style.font.caption
                }

                Repeater {
                  model: window.historyMessages

                  Column {
                    id: historyRow
                    required property var modelData
                    required property int index
                    width: parent.width
                    spacing: Style.spacing.xs

                    readonly property var olderMessage: index > 0 ? window.historyMessages[index - 1] : null
                    readonly property bool showDayDivider: olderMessage === null
                      || window.dayKey(olderMessage.timestamp) !== window.dayKey(modelData.timestamp)

                    Item {
                      // Column (historyRow) forbids anchors on its direct
                      // children, so the centered pill lives one level
                      // down inside this plain, full-width wrapper.
                      visible: historyRow.showDayDivider
                      width: parent.width
                      height: visible ? dayChip.implicitHeight : 0

                      BorderSurface {
                        id: dayChip
                        anchors.centerIn: parent
                        implicitWidth: dayLabel.implicitWidth + Style.space(16)
                        implicitHeight: dayLabel.implicitHeight + Style.space(6)
                        radius: Style.cornerRadius > 0 ? height / 2 : Style.cornerRadius
                        color: Style.normalFillFor(window.foreground, window.accent)
                        borderSpec: Border.controlSpec("normal", window.foreground, window.accent)

                        Text {
                          id: dayLabel
                          anchors.centerIn: parent
                          text: window.formatChatDay(historyRow.modelData.timestamp)
                          color: window.dim
                          font.family: window.fontFamily
                          font.pixelSize: Style.font.caption
                          font.bold: true
                        }
                      }
                    }

                    HistoryMessage {
                    width: parent.width
                    content: modelData.content || ""
                    isSender: !!modelData.isSender
                    timestampLabel: window.formatChatTime(modelData.timestamp)
                    deliveryStatus: modelData.deliveryStatus || ""
                    mimeType: modelData.mimeType || ""
                    messageId: modelData.id
                    thumbnailState: window.thumbnails[modelData.id]
                    fileName: modelData.file ? (modelData.file.fileName || "") : ""
                    fileMetaLabel: modelData.file
                      ? (window.service.formatBytes(modelData.file.fileSize) + " · " + (modelData.file.status || ""))
                      : ""
                    filePath: modelData.file ? (modelData.file.filePath || "") : ""
                    fileStatus: modelData.file ? (modelData.file.status || "") : ""
                    fileTransferId: modelData.fileTransferId || null
                    foreground: window.foreground
                    accent: window.accent
                    dim: window.dim
                    fontFamily: window.fontFamily
                    onOpenFileRequested: window.service.openFile(modelData.file.filePath)
                    onCopyTextRequested: window.service.copyText(modelData.content)
                    onRevealRequested: window.service.openFile(window.parentDirOf(modelData.file.filePath))
                    onRetryRequested: window.service.retry(modelData.fileTransferId, null)
                    onShowFullTextRequested: window.showLongText(modelData.content)
                    onThumbnailNeeded: window.requestThumbnail(modelData.id)
                    }
                  }
                }
              }
            }

            Row {
              id: composerRow
              anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
              spacing: Style.spacing.xs

              TextField {
                id: composerField
                width: parent.width - Style.space(150)
                placeholderText: "Type message..."
                onAccepted: window.sendComposerText()
              }

              Button {
                text: "Send"
                fontFamily: window.fontFamily
                onClicked: window.sendComposerText()
              }

              PanelActionButton {
                iconText: "󰉋"
                tooltipText: "Send files"
                fontFamily: window.fontFamily
                onClicked: window.sendFiles(window.selectedDeviceId)
              }

              PanelActionButton {
                iconText: "󰅌"
                tooltipText: "Send clipboard"
                fontFamily: window.fontFamily
                onClicked: window.service.sendClipboard(window.selectedDeviceId, null)
              }
            }
          }
        }
      }
    }

    ConfirmDialog {
      id: pairConfirmDialog
      anchors.fill: parent
      opened: !!(window.service && window.service.pairingDialog && !window.service.pairingDialog.isError)
      message: window.service && window.service.pairingDialog
        ? ("Accept pairing request from " + (window.service.pairingDialog.deviceName || window.service.pairingDialog.deviceId) + "?")
        : ""
      confirmText: "Accept"
      cancelText: "Decline"
      fontFamily: window.fontFamily
      onConfirmed: {
        if (window.service.pairingDialog) window.service.acceptPair(window.service.pairingDialog.deviceId, null)
      }
      onCanceled: {
        if (window.service.pairingDialog) window.service.rejectPair(window.service.pairingDialog.deviceId, null)
      }
    }

    ConfirmDialog {
      id: forgetConfirmDialog
      anchors.fill: parent
      opened: !!window.forgetTargetDevice
      message: window.forgetTargetDevice
        ? ("Forget " + (window.forgetTargetDevice.deviceName || window.forgetTargetDevice.deviceId) + "? You'll need to pair again to reconnect.")
        : ""
      confirmText: "Forget"
      cancelText: "Cancel"
      fontFamily: window.fontFamily
      onConfirmed: {
        if (window.forgetTargetDevice) {
          window.service.unpair(window.forgetTargetDevice.deviceId, null)
          if (window.selectedDeviceId === window.forgetTargetDevice.deviceId) {
            window.clearSelection()
          }
        }
        window.forgetTargetDevice = null
      }
      onCanceled: window.forgetTargetDevice = null
    }

    ConfirmDialog {
      id: applyForceConfirmDialog
      anchors.fill: parent
      opened: window.applyForceConfirmOpen
      message: "A file transfer is still in progress. Restart to update anyway?"
      confirmText: "Restart"
      cancelText: "Wait"
      fontFamily: window.fontFamily
      onConfirmed: {
        window.applyForceConfirmOpen = false
        window.applyUpdate(true)
      }
      onCanceled: window.applyForceConfirmOpen = false
    }

    // Full-text viewer for messages collapsed by HistoryMessage's
    // "Show more" — a scrollable read-only overlay. PromptOverlay doesn't
    // fit (it's a single-line text-entry control, not a scrollable reader),
    // so this is a minimal purpose-built overlay in the same
    // scrim-plus-card idiom ConfirmDialog already uses above.
    Item {
      id: longTextViewer
      anchors.fill: parent
      visible: window.longTextViewerOpen

      Rectangle {
        anchors.fill: parent
        color: Util.alpha(Color.background, 0.7)
        MouseArea { anchors.fill: parent; onClicked: window.longTextViewerOpen = false }
      }

      BorderSurface {
        width: Math.min(parent.width - Style.space(64), Style.space(520))
        height: Math.min(parent.height - Style.space(64), Style.space(420))
        anchors.centerIn: parent
        color: window.opaqueSurface
        borderSpec: Border.controlSpec("selected", window.foreground, window.accent)
        radius: Style.cornerRadius
        padding: Style.spacing.md

        MouseArea { anchors.fill: parent; onClicked: {} }

        Column {
          anchors.fill: parent
          spacing: Style.spacing.sm

          Flickable {
            width: parent.width
            height: parent.height - viewerActions.height - Style.spacing.sm
            contentWidth: width
            contentHeight: viewerText.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            Text {
              id: viewerText
              width: parent.width
              text: window.longTextViewerContent
              wrapMode: Text.WrapAtWordBoundaryOrAnywhere
              color: window.foreground
              font.family: window.fontFamily
              font.pixelSize: Style.font.bodySmall
            }
          }

          Row {
            id: viewerActions
            anchors.right: parent.right
            spacing: Style.spacing.xs

            Button {
              text: "Copy"
              fontFamily: window.fontFamily
              onClicked: window.service.copyText(window.longTextViewerContent)
            }

            Button {
              text: "Close"
              fontFamily: window.fontFamily
              onClicked: window.longTextViewerOpen = false
            }
          }
        }
      }
    }
  }
}
