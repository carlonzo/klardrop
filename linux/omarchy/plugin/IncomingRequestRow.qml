import QtQuick
import qs.Commons
import qs.Ui

// One pending entry in the "INCOMING REQUESTS" list: a pairing or
// incoming-transfer request. `pendingAuth` hides the accept/reject buttons
// for entries that don't need a decision (e.g. already-accepted transfers
// still shown while they start).
BorderSurface {
  id: root

  property string deviceId: ""
  property string deviceName: ""
  property string status: ""
  property bool pendingAuth: false
  // /state's `incoming[]` entries: file summary (fileNames capped to 10
  // server-side even when fileCount is larger) or a text preview.
  property int fileCount: 0
  property var fileNames: []
  // Caller-formatted (Main.formatBytes), same pattern TransferRow uses.
  property string totalSizeLabel: ""
  property string textPreview: ""

  property color foreground: Color.foreground
  property color accent: Color.accent
  property color urgent: Color.urgent
  property string fontFamily: Style.font.family

  signal acceptRequested()
  signal rejectRequested()
  signal openRequested()
  signal dismissRequested()

  // A completed incoming transfer stays in `/state`'s `incoming[]` (as a
  // "received card") until explicitly dismissed — it gets Open/Dismiss
  // instead of Accept/Reject.
  readonly property bool isReceivedCard: root.status === "Completed"

  // /state's `status` is the raw ReceiveMessageStatus subclass name
  // (MessageReceiver.kt). The verb stays generic; `summaryLabel` below
  // carries what's actually being sent.
  readonly property string statusLabel: {
    switch (root.status) {
      case "PendingAuthorization": return "wants to send"
      case "Started": return "sending…"
      case "Completed": return "sent"
      case "Failed": return "transfer failed"
      default: return root.status || "request"
    }
  }

  readonly property string summaryLabel: {
    if (root.fileCount > 0) {
      var label = root.fileCount + (root.fileCount === 1 ? " file" : " files")
      if (root.totalSizeLabel !== "") label += " (" + root.totalSizeLabel + ")"
      if (root.fileNames.length > 0) {
        var shown = root.fileNames.join(", ")
        var extra = root.fileCount - root.fileNames.length
        if (extra > 0) shown += ", +" + extra + " more"
        label += ": " + shown
      }
      return label
    }
    return root.textPreview
  }

  implicitHeight: content.implicitHeight + Style.spacing.xs * 2
  color: Style.normalFillFor(root.foreground, root.accent)
  borderSpec: Border.controlSpec("normal", root.foreground, root.accent)
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

    Column {
      width: parent.width - ((root.pendingAuth || root.isReceivedCard) ? Style.space(60) : 0)
      spacing: 1
      anchors.verticalCenter: parent.verticalCenter

      Text {
        text: (root.deviceName || root.deviceId || "Device") + " " + root.statusLabel
        textFormat: Text.PlainText
        color: root.foreground
        font.family: root.fontFamily
        font.pixelSize: Style.font.bodySmall
        elide: Text.ElideRight
        width: parent.width
      }

      Text {
        visible: root.summaryLabel !== ""
        text: root.summaryLabel
        textFormat: Text.PlainText
        color: Qt.darker(root.foreground, 1.4)
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
        elide: Text.ElideRight
        width: parent.width
      }
    }

    Row {
      anchors.verticalCenter: parent.verticalCenter
      spacing: Style.spacing.xs
      visible: root.pendingAuth

      PanelActionButton {
        iconText: "󰄬"
        tooltipText: "Accept"
        fontFamily: root.fontFamily
        onClicked: root.acceptRequested()
      }

      PanelActionButton {
        iconText: "󰅖"
        tooltipText: "Reject"
        hoverColor: root.urgent
        fontFamily: root.fontFamily
        onClicked: root.rejectRequested()
      }
    }

    Row {
      anchors.verticalCenter: parent.verticalCenter
      spacing: Style.spacing.xs
      visible: root.isReceivedCard

      PanelActionButton {
        iconText: "󰏌"
        tooltipText: "Open"
        fontFamily: root.fontFamily
        onClicked: root.openRequested()
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
