import QtQuick
import qs.Commons
import qs.Ui

// QR browser share overlay: lets a phone without Klardrop grab files over the
// same LAN via a short-lived HTTPS link. Purely presentational + API-driving,
// same split as PromptOverlay/TransferRow: the caller (Main.qml's entry
// point, wired up separately) supplies `service` (Main.qml itself, for its
// `request(method, path, body, callback)` helper) and `paths`, and toggles
// `opened`; this component owns starting/stopping the daemon-side share and
// drawing the QR code from the `qr` matrix rows the daemon returns.
BorderSurface {
  id: root

  property var service: null
  property var paths: []
  property bool opened: false

  property string shareUrl: ""
  property real expiresAt: 0
  property var qrRows: []
  property string errorText: ""
  property bool starting: false

  property color foreground: Color.foreground
  property color accent: Color.accent
  property string fontFamily: Style.font.family

  signal closed()

  visible: opened
  implicitWidth: 260
  implicitHeight: content.implicitHeight + Style.spacing.md * 2
  color: Style.normalFillFor(root.foreground, root.accent)
  borderSpec: Border.controlSpec("normal", root.foreground, root.accent)
  radius: Style.cornerRadius

  function reset() {
    shareUrl = ""
    expiresAt = 0
    qrRows = []
    errorText = ""
    starting = false
  }

  function start() {
    if (!service || !paths || paths.length === 0) {
      errorText = "No files selected"
      return
    }
    reset()
    starting = true
    service.request("POST", "/qr-share", { paths: paths }, function(err, resp) {
      root.starting = false
      if (err) {
        root.errorText = (err && err.message) ? err.message : "Failed to start QR share"
        return
      }
      root.shareUrl = resp && resp.url ? resp.url : ""
      root.expiresAt = resp && resp.expiresAt ? resp.expiresAt : 0
      root.qrRows = resp && resp.qr ? resp.qr : []
    })
  }

  function stop() {
    // Fire-and-forget: the overlay closes regardless of whether the daemon's
    // stop round-trip succeeds (matches PromptOverlay/TransferRow — no
    // in-flight-request state machine in these presentational components).
    if (service) service.request("POST", "/qr-share/stop", {}, function() {})
    reset()
  }

  onOpenedChanged: {
    if (opened) start()
    else stop()
  }

  onVisibleChanged: {
    if (!visible) closed()
  }

  // Countdown display only — the daemon (not this timer) is the source of
  // truth for actual expiry; this just re-evaluates secondsLeft once a second.
  property real nowMs: Date.now()
  Timer {
    interval: 1000
    repeat: true
    running: root.opened && root.expiresAt > 0
    onTriggered: root.nowMs = Date.now()
  }
  readonly property int secondsLeft: root.expiresAt > 0
    ? Math.max(0, Math.round((root.expiresAt - root.nowMs) / 1000))
    : 0

  Column {
    id: content
    anchors {
      left: parent.left
      right: parent.right
      top: parent.top
      margins: Style.spacing.md
    }
    spacing: Style.spacing.sm

    Text {
      text: "Scan to receive"
      color: root.foreground
      font.family: root.fontFamily
      font.pixelSize: Style.font.subtitle
      font.bold: true
    }

    // White square with a quiet-zone margin so phone cameras can lock on —
    // the daemon's `qr` response is the raw module matrix with no padding.
    Rectangle {
      id: qrBox
      width: 220
      height: 220
      anchors.horizontalCenter: parent.horizontalCenter
      color: "white"
      radius: Style.cornerRadius
      visible: root.qrRows.length > 0

      Canvas {
        id: qrCanvas
        anchors.fill: parent
        anchors.margins: 14
        renderTarget: Canvas.FramebufferObject

        onPaint: {
          var ctx = getContext("2d")
          ctx.clearRect(0, 0, width, height)
          var rows = root.qrRows
          var n = rows.length
          if (n === 0) return
          var cell = Math.min(width, height) / n
          ctx.fillStyle = "black"
          for (var r = 0; r < n; r++) {
            var row = rows[r]
            for (var c = 0; c < row.length; c++) {
              if (row.charAt(c) === "1") {
                ctx.fillRect(Math.floor(c * cell), Math.floor(r * cell), Math.ceil(cell), Math.ceil(cell))
              }
            }
          }
        }

        Connections {
          target: root
          function onQrRowsChanged() { qrCanvas.requestPaint() }
        }
      }
    }

    Text {
      visible: root.starting
      text: "Starting…"
      color: root.foreground
      font.family: root.fontFamily
      font.pixelSize: Style.font.bodySmall
    }

    Text {
      visible: root.errorText !== ""
      text: root.errorText
      textFormat: Text.PlainText
      color: Color.urgent
      font.family: root.fontFamily
      font.pixelSize: Style.font.bodySmall
      wrapMode: Text.WordWrap
      width: parent.width
    }

    Text {
      visible: root.shareUrl !== ""
      text: root.shareUrl
      textFormat: Text.PlainText
      color: root.foreground
      font.family: root.fontFamily
      font.pixelSize: Style.font.caption
      elide: Text.ElideMiddle
      width: parent.width
      horizontalAlignment: Text.AlignHCenter
    }

    Text {
      visible: root.secondsLeft > 0
      text: "Link expires in " + Math.floor(root.secondsLeft / 3600) + "h " + Math.floor((root.secondsLeft % 3600) / 60) + "m"
      color: Qt.darker(root.foreground, 1.4)
      font.family: root.fontFamily
      font.pixelSize: Style.font.caption
      horizontalAlignment: Text.AlignHCenter
      width: parent.width
    }

    Button {
      text: "Stop sharing"
      fontFamily: root.fontFamily
      anchors.horizontalCenter: parent.horizontalCenter
      onClicked: root.opened = false
    }
  }
}
