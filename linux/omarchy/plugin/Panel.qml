import QtQuick
import QtQuick.Controls
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Ui

Panel {
  id: root
  moduleName: "klardrop.omarchy"
  ipcTarget: "klardrop.omarchy"
  // Panel (qs.Ui) extends a plain QtQuick Item, which does not flow a
  // child's implicit size up to its own implicitWidth/implicitHeight the
  // way Control-based types do. The bar module slot sizes itself off
  // `activeItem.implicitWidth`/`implicitHeight` (see Bar.qml
  // debugBarGeometry), so without this the slot stays 0x0 in every
  // orientation regardless of whether `bar.vertical` is correct.
  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight
  manageIpc: false

  readonly property color foreground: bar ? bar.foreground : Color.foreground
  readonly property color urgent: bar ? bar.urgent : Color.urgent
  readonly property color accent: Color.accent
  readonly property color dim: Qt.darker(foreground, 1.4)
  readonly property color surface: Color.popups.background
  readonly property string fontFamily: bar ? bar.fontFamily : Style.font.family

  // Alias so StandaloneWindow's own `service` property (same name) doesn't
  // shadow this id inside the Loader's inline Component below.
  property alias mainService: service

  Main {
    id: service
    settings: root.settings
  }

  function sendFiles(deviceId) {
    service.selectAndSendFiles(deviceId, null)
  }

  // Standalone window: lazily loaded so it (and its items) are freed when
  // closed, and single-instance because it's the one Loader below — a
  // second `openWindow` while it's already open just refocuses it instead
  // of creating another.
  function openStandaloneWindow() {
    root.close()
    windowLoader.active = true
    if (!focusWindowProc.running) focusWindowProc.running = true
  }

  // Closing from IPC (as opposed to the window's own titlebar/WM close) —
  // used by the leak-check harness so it doesn't need window-manager access.
  function closeStandaloneWindow() {
    windowLoader.active = false
  }

  // Debounced by StandaloneWindow itself; called at most ~once per 800ms
  // while the user drags a resize handle. Persisted into this plugin's own
  // shell.json entry (same path omacom.elsewhen's clock uses for its city
  // list) so the size survives both re-opens and shell restarts.
  function persistWindowSize(w, h) {
    var entry = { id: root.moduleName }
    for (var k in root.settings) if (k !== "id") entry[k] = root.settings[k]
    entry.windowWidth = w
    entry.windowHeight = h
    root.settings = entry
    if (root.bar && root.bar.shell && typeof root.bar.shell.updateEntryInline === "function")
      root.bar.shell.updateEntryInline(root.moduleName, entry)
  }

  Loader {
    id: windowLoader
    active: false
    sourceComponent: StandaloneWindow {
      service: root.mainService
      foreground: root.foreground
      accent: root.accent
      urgent: root.urgent
      dim: root.dim
      surface: root.surface
      fontFamily: root.fontFamily
      initialWidth: root.setting("windowWidth", 1000)
      initialHeight: root.setting("windowHeight", 700)
      onSizeResolved: function(w, h) { root.persistWindowSize(w, h) }
      onVisibleChanged: if (!visible) windowLoader.active = false
    }
  }

  // Single-instance focus. Hyprland 0.56's `dispatch` takes one Lua-call
  // argv token (the classic multi-arg "focuswindow <selector>" form no
  // longer parses) — confirmed against a running window via
  // `hyprctl activewindow -j`. The window's title is a fixed literal with
  // no user input folded in, so this stays a plain argv Process (no shell
  // involved). Harmless no-op if the window isn't mapped yet (e.g. the very
  // first open, which already gets focus itself).
  Process {
    id: focusWindowProc
    command: ["hyprctl", "dispatch", "hl.dsp.focus({ window = \"title:^Klardrop$\" })"]
  }

  IpcHandler {
    enabled: true
    target: root.ipcTarget

    function open() { root.open() }
    function close() { root.close() }
    function toggle() { root.toggle() }
    function show() { root.open() }
    function hide() { root.close() }
    function openWindow() { root.openStandaloneWindow() }
    function closeWindow() { root.closeStandaloneWindow() }
  }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: "󰅟"
    active: service.hasPendingBadge
    useActiveColor: true
    activeColor: root.urgent
    tooltipText: "Klardrop"
    onPressed: function(buttonCode) {
      root.toggle()
    }
  }

  KeyboardPanel {
    id: panel
    anchorItem: button
    owner: root
    bar: root.bar
    open: root.opened
    focusTarget: keyCatcher
    contentWidth: panel.fittedContentWidth(Style.space(380))
    contentHeight: panel.fittedContentHeight(panelContent.implicitHeight, Style.space(560))

    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent

      onCloseRequested: root.close()
      onTabRequested: function(direction) { root.switchPanel(direction) }

      Flickable {
        id: panelFlick
        anchors.fill: parent
        contentWidth: width
        contentHeight: panelContent.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.VerticalFlick
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        Column {
          id: panelContent
          width: parent.width
          spacing: Style.spacing.md

          PanelHero {
            title: "Klardrop"
            meta: service.daemonRunning
              ? (service.selfDevice ? service.selfDevice.deviceName : "Connected")
              : "Daemon Offline"
            detail: service.selfDevice ? ("ID: " + service.selfDevice.deviceId) : ""
            fontFamily: root.fontFamily
            foreground: root.foreground
            iconComponent: Component {
              Text {
                text: "󰅟"
                font.family: root.fontFamily
                font.pixelSize: Style.font.display
                color: root.foreground
              }
            }
          }

          Row {
            width: parent.width

            Button {
              text: "Open Klardrop"
              iconText: "󰽊"
              bordered: true
              fontFamily: root.fontFamily
              onClicked: root.openStandaloneWindow()
            }
          }

          // Offline state
          Column {
            width: parent.width
            spacing: Style.spacing.sm
            visible: !service.daemonRunning

            Text {
              width: parent.width
              text: "Klardrop daemon is not running."
              color: root.dim
              font.family: root.fontFamily
              font.pixelSize: Style.font.body
              wrapMode: Text.WordWrap
            }

            Button {
              text: "Start Klardrop"
              iconText: "󰐥"
              bordered: true
              selected: true
              fontFamily: root.fontFamily
              onClicked: service.startDaemon()
            }
          }

          // Online state content
          Column {
            width: parent.width
            spacing: Style.spacing.md
            visible: service.daemonRunning

            // Incoming requests
            Column {
              width: parent.width
              spacing: Style.spacing.xs
              visible: service.incomingRequests.length > 0

              PanelSectionHeader {
                text: "INCOMING REQUESTS"
                fontFamily: root.fontFamily
                foreground: root.foreground
              }

              Repeater {
                model: service.incomingRequests

                IncomingRequestRow {
                  required property var modelData
                  width: parent.width
                  deviceId: modelData.deviceId || ""
                  deviceName: modelData.deviceName || ""
                  status: modelData.status || ""
                  pendingAuth: !!modelData.pendingAuth
                  fileCount: modelData.fileCount || 0
                  fileNames: modelData.fileNames || []
                  totalSizeLabel: service.formatBytes(modelData.totalSize)
                  textPreview: modelData.text || ""
                  foreground: root.foreground
                  accent: root.accent
                  urgent: root.urgent
                  fontFamily: root.fontFamily
                  onAcceptRequested: service.acceptIncoming(modelData.receiveId, modelData.deviceId, null)
                  onRejectRequested: service.rejectIncoming(modelData.receiveId, null)
                  onOpenRequested: service.openIncoming(modelData.receiveId, null)
                  onDismissRequested: service.dismissIncoming(modelData.receiveId, null)
                }
              }
            }

            // In-flight file transfers
            Column {
              width: parent.width
              spacing: Style.spacing.xs
              visible: service.transfers.length > 0

              PanelSectionHeader {
                text: "TRANSFERS (" + service.transfers.length + ")"
                fontFamily: root.fontFamily
                foreground: root.foreground
              }

              Repeater {
                model: service.transfers

                TransferRow {
                  required property var modelData
                  width: parent.width
                  isSender: !!modelData.isSender
                  fileName: modelData.fileName || ""
                  totalSize: Number(modelData.totalSize || 0)
                  transferredSize: Number(modelData.transferredSize || 0)
                  sizeLabel: service.formatBytes(modelData.transferredSize) + " of " + service.formatBytes(modelData.totalSize) + (modelData.deviceId ? (" • " + modelData.deviceId) : "")
                  foreground: root.foreground
                  accent: root.accent
                  dim: root.dim
                  fontFamily: root.fontFamily
                }
              }
            }

            // Trusted devices: quick actions only (send file / clipboard).
            Column {
              width: parent.width
              spacing: Style.spacing.sm

              PanelSectionHeader {
                text: "TRUSTED DEVICES (" + service.pairedDevices.length + ")"
                fontFamily: root.fontFamily
                foreground: root.foreground
              }

              Text {
                visible: service.pairedDevices.length === 0
                text: "No paired devices yet."
                color: root.dim
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
              }

              Repeater {
                model: service.pairedDevices

                DeviceRow {
                  required property var modelData
                  width: parent.width
                  paired: true
                  showMessageButton: false
                  showForgetButton: false
                  deviceId: modelData.deviceId || ""
                  deviceName: modelData.deviceName || ""
                  glyph: service.deviceGlyph(modelData.deviceType)
                  reachability: modelData.reachability || ""
                  unreadCount: modelData.unreadCount || 0
                  foreground: root.foreground
                  accent: root.accent
                  urgent: root.urgent
                  dim: root.dim
                  fontFamily: root.fontFamily
                  onSendFilesRequested: root.sendFiles(modelData.deviceId)
                  onSendClipboardRequested: service.sendClipboard(modelData.deviceId, null)
                  onFilesDropped: function(urls) {
                    var paths = service.dropUrlsToPaths(urls)
                    if (paths.length > 0) service.sendFile(modelData.deviceId, paths, null)
                  }
                }
              }

              PanelSeparator {}

              PanelSectionHeader {
                text: "NEARBY DEVICES (" + service.nearbyDevices.length + ")"
                fontFamily: root.fontFamily
                foreground: root.foreground
              }

              Text {
                visible: service.nearbyDevices.length === 0
                text: "Scanning for nearby devices..."
                color: root.dim
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
              }

              Repeater {
                model: service.nearbyDevices

                DeviceRow {
                  required property var modelData
                  width: parent.width
                  paired: false
                  deviceId: modelData.deviceId || ""
                  deviceName: modelData.deviceName || ""
                  glyph: service.deviceGlyph(modelData.deviceType)
                  reachability: modelData.reachability || ""
                  foreground: root.foreground
                  accent: root.accent
                  urgent: root.urgent
                  dim: root.dim
                  fontFamily: root.fontFamily
                  onPairRequested: service.pair(modelData.deviceId, null)
                }
              }
            }
          }
        }
      }
    }

    ConfirmDialog {
      id: pairConfirmDialog
      opened: !!service.pairingDialog && !service.pairingDialog.isError
      message: service.pairingDialog
        ? ("Accept pairing request from " + (service.pairingDialog.deviceName || service.pairingDialog.deviceId) + "?")
        : ""
      confirmText: "Accept"
      cancelText: "Decline"
      fontFamily: root.fontFamily
      onConfirmed: {
        if (service.pairingDialog) service.acceptPair(service.pairingDialog.deviceId, null)
      }
      onCanceled: {
        if (service.pairingDialog) service.rejectPair(service.pairingDialog.deviceId, null)
      }
    }
  }
}
