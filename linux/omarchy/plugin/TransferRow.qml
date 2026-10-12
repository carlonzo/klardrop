import QtQuick
import qs.Commons
import qs.Ui

// One in-flight (or just-finished) file transfer, sending or receiving.
// The caller formats `sizeLabel` (formatBytes lives on Main, shared by both
// surfaces) — this component only lays it out and draws the progress bar.
BorderSurface {
  id: root

  property bool isSender: false
  property string fileName: ""
  property real totalSize: 0
  property real transferredSize: 0
  property string sizeLabel: ""

  property color foreground: Color.foreground
  property color accent: Color.accent
  property color dim: Qt.darker(foreground, 1.4)
  property string fontFamily: Style.font.family

  readonly property real progress: totalSize > 0
    ? Math.max(0, Math.min(1, transferredSize / totalSize))
    : 0

  implicitHeight: col.implicitHeight + Style.spacing.sm * 2
  color: Style.normalFillFor(root.foreground, root.accent)
  borderSpec: Border.controlSpec("normal", root.foreground, root.accent)
  radius: Style.cornerRadius

  Column {
    id: col
    anchors {
      left: parent.left
      right: parent.right
      top: parent.top
      margins: Style.spacing.sm
    }
    spacing: Style.spacing.xs

    Row {
      width: parent.width

      Text {
        text: (root.isSender ? "󰈔 󰞒 " : "󰈔 󰞓 ") + (root.fileName || "File")
        textFormat: Text.PlainText
        color: root.foreground
        font.family: root.fontFamily
        font.pixelSize: Style.font.bodySmall
        font.bold: true
        elide: Text.ElideRight
        width: parent.width - statusText.width - Style.spacing.xs
      }

      Text {
        id: statusText
        text: root.totalSize > 0 ? (Math.round(root.progress * 100) + "%") : "Receiving…"
        color: Color.accent
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
      }
    }

    Item {
      width: parent.width
      height: Style.space(4)

      Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Style.selectedFillFor(root.foreground, root.accent)
      }

      Rectangle {
        height: parent.height
        radius: height / 2
        color: Color.accent
        width: parent.width * root.progress
      }
    }

    Text {
      text: root.sizeLabel
      color: root.dim
      font.family: root.fontFamily
      font.pixelSize: Style.font.caption
      elide: Text.ElideRight
      width: parent.width
    }
  }
}
