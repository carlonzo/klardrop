import QtQuick
import qs.Commons
import qs.Ui

// One entry from /state's `notifications[]` — currently always a
// PeerRevokedTrust ("<device> no longer trusts this device", the peer
// unpaired from their end). Offers Dismiss or re-pairing.
//
// Purely presentational, same pattern as IncomingRequestRow: plain Text
// (never StyledText/RichText) so a crafted device name can't inject markup.
BorderSurface {
  id: root

  property string deviceId: ""
  property string deviceName: ""
  // Caller-supplied so this same card shape covers both a PeerRevokedTrust
  // notification ("<device> no longer trusts this device") and a
  // pairing-dialog error (the daemon's own errorMessage) without this file
  // hardcoding either wording.
  property string message: (root.deviceName || root.deviceId || "A device") + " no longer trusts this device"
  // Hides the "Pair again" action for a plain error card that isn't about
  // trust (e.g. "device unreachable" while pairing).
  property bool showPairAction: true

  property color foreground: Color.foreground
  property color accent: Color.accent
  property color urgent: Color.urgent
  property string fontFamily: Style.font.family

  signal dismissRequested()
  signal pairRequested()

  implicitHeight: content.implicitHeight + Style.spacing.xs * 2
  color: Style.normalFillFor(root.foreground, root.urgent)
  borderSpec: Border.controlSpec("normal", root.foreground, root.urgent)
  radius: Style.cornerRadius
  padding: Style.spacing.xs

  Row {
    id: content
    anchors.left: parent.left
    anchors.right: parent.right
    anchors.verticalCenter: parent.verticalCenter
    anchors.leftMargin: Style.space(6)
    anchors.rightMargin: Style.space(6)
    spacing: Style.spacing.xs

    Text {
      textFormat: Text.PlainText
      width: parent.width - (root.showPairAction ? Style.space(60) : Style.space(30))
      text: root.message
      color: root.foreground
      font.family: root.fontFamily
      font.pixelSize: Style.font.bodySmall
      wrapMode: Text.WordWrap
    }

    Row {
      anchors.verticalCenter: parent.verticalCenter
      spacing: Style.spacing.xs

      PanelActionButton {
        visible: root.showPairAction
        iconText: "󰌹"
        tooltipText: "Pair again"
        fontFamily: root.fontFamily
        onClicked: root.pairRequested()
      }

      PanelActionButton {
        iconText: "󰅖"
        tooltipText: "Dismiss"
        hoverColor: root.urgent
        fontFamily: root.fontFamily
        onClicked: root.dismissRequested()
      }
    }
  }
}
