import QtQuick
import qs.Commons
import qs.Ui

// One row in a device list — used for both paired ("trusted") devices and
// nearby ("discovered") devices, in both the bar panel and the standalone
// window's sidebar. `paired` picks the action-button set and default row
// height; `selected` lets the window's sidebar highlight the currently-open
// chat.
//
// Purely presentational: takes device fields via properties, reports intent
// via signals. Makes no API calls. Caller sets `width`.
BorderSurface {
  id: root

  property string deviceId: ""
  property string deviceName: ""
  property string glyph: "󰒋"
  property string reachability: ""
  property int unreadCount: 0
  property bool paired: true
  property bool selected: false
  // The trimmed bar panel (M2) only offers send file/clipboard — messaging
  // and unpairing now live in the standalone window's sidebar.
  property bool showMessageButton: true
  property bool showForgetButton: true

  property color foreground: Color.foreground
  property color accent: Color.accent
  property color urgent: Color.urgent
  property color dim: Qt.darker(foreground, 1.4)
  property string fontFamily: Style.font.family

  readonly property bool showUnreadBadge: root.paired && root.unreadCount > 0
  // Only a trusted device is a valid drop target — an untrusted/nearby row
  // has no way to receive a file until it's paired.
  property bool dragHover: false

  signal pairRequested()
  signal sendFilesRequested()
  signal sendClipboardRequested()
  signal sendMessageRequested()
  signal forgetRequested()
  // `urls`: the raw `drop.urls` array from the DropArea below, still
  // file:// QUrls — the caller converts via Main's `dropUrlsToPaths()`.
  signal filesDropped(var urls)

  implicitHeight: paired ? Style.space(42) : Style.space(38)
  color: root.dragHover
    ? Style.selectedFillFor(root.foreground, root.accent)
    : (root.selected
      ? Style.selectedFillFor(root.foreground, root.accent)
      : Style.normalFillFor(root.foreground, root.accent))
  borderSpec: root.dragHover
    ? Border.controlSpec("selected", root.foreground, root.accent)
    : Border.controlSpec("normal", root.foreground, root.accent)
  radius: Style.cornerRadius
  padding: Style.spacing.xs

  DropArea {
    id: dropArea
    anchors.fill: parent
    enabled: root.paired
    keys: ["text/uri-list"]
    onEntered: root.dragHover = true
    onExited: root.dragHover = false
    onDropped: function(drop) {
      root.dragHover = false
      root.filesDropped(drop.urls)
    }
  }

  Row {
    anchors.fill: parent
    anchors.leftMargin: Style.space(8)
    anchors.rightMargin: Style.space(8)
    spacing: Style.spacing.sm

    Text {
      text: root.glyph
      font.family: root.fontFamily
      font.pixelSize: Style.font.icon
      color: root.foreground
      anchors.verticalCenter: parent.verticalCenter
    }

    Column {
      anchors.verticalCenter: parent.verticalCenter
      // Base overhead (icon column, margins) plus ~24px per visible action
      // button; sendFiles/sendClipboard are always shown, the rest optional.
      readonly property int visiblePairedButtons: 2
        + (root.showMessageButton ? 1 : 0)
        + (root.showForgetButton ? 1 : 0)
      readonly property int badgeReserve: root.showUnreadBadge ? Style.space(28) : 0
      width: parent.width - badgeReserve - (root.paired ? (Style.space(50) + visiblePairedButtons * Style.space(24)) : Style.space(100))
      spacing: 1

      Text {
        text: root.deviceName || root.deviceId
        textFormat: Text.PlainText
        font.family: root.fontFamily
        font.pixelSize: Style.font.bodySmall
        font.bold: root.paired
        color: root.foreground
        elide: Text.ElideRight
        width: parent.width
      }

      Text {
        text: root.reachability || (root.paired ? "unknown" : "discovered")
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
        color: root.paired
          ? (root.reachability === "reachable" ? Color.accent : root.dim)
          : root.dim
      }
    }

    BorderSurface {
      id: unreadBadge
      visible: root.showUnreadBadge
      anchors.verticalCenter: parent.verticalCenter
      implicitWidth: Math.max(Style.space(16), badgeText.implicitWidth + Style.space(8))
      implicitHeight: Style.space(16)
      radius: height / 2
      color: Style.selectedFillFor(root.foreground, root.accent)
      borderSpec: Border.controlSpec("selected", root.foreground, root.accent)

      Text {
        id: badgeText
        anchors.centerIn: parent
        text: root.unreadCount > 99 ? "99+" : String(root.unreadCount)
        color: root.accent
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
        font.bold: true
      }
    }

    Row {
      anchors.verticalCenter: parent.verticalCenter
      spacing: Style.space(2)
      visible: root.paired

      PanelActionButton {
        iconText: "󰉋"
        tooltipText: "Send files"
        fontFamily: root.fontFamily
        onClicked: root.sendFilesRequested()
      }

      PanelActionButton {
        iconText: "󰅌"
        tooltipText: "Send clipboard"
        fontFamily: root.fontFamily
        onClicked: root.sendClipboardRequested()
      }

      PanelActionButton {
        visible: root.showMessageButton
        iconText: "󰭹"
        tooltipText: "Send message"
        fontFamily: root.fontFamily
        onClicked: root.sendMessageRequested()
      }

      PanelActionButton {
        visible: root.showForgetButton
        iconText: "󰅖"
        tooltipText: "Unpair"
        hoverColor: root.urgent
        fontFamily: root.fontFamily
        onClicked: root.forgetRequested()
      }
    }

    Button {
      anchors.verticalCenter: parent.verticalCenter
      visible: !root.paired
      text: "Pair"
      iconText: "󰌹"
      fontFamily: root.fontFamily
      fontSize: Style.font.caption
      onClicked: root.pairRequested()
    }
  }
}
