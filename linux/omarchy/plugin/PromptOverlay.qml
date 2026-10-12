import QtQuick
import QtQuick.Controls
import qs.Commons
import qs.Ui

// Inline text-entry overlay used for both "rename this device" and "send
// message to <device>" prompts. Purely presentational: the caller supplies
// `label`, seeds/reads the edited text via `text`, and decides what to do
// with it on `submitted`/`canceled` — this component holds no protocol/API
// knowledge.
BorderSurface {
  id: root

  property bool opened: false
  property string label: ""
  property alias text: field.text

  property color foreground: Color.foreground
  property color accent: Color.accent
  property string fontFamily: Style.font.family

  signal submitted(string text)
  signal canceled()

  visible: opened
  implicitHeight: content.implicitHeight + Style.spacing.sm * 2
  color: Style.normalFillFor(root.foreground, root.accent)
  borderSpec: Border.controlSpec("normal", root.foreground, root.accent)
  radius: Style.cornerRadius

  Column {
    id: content
    anchors {
      left: parent.left
      right: parent.right
      top: parent.top
      margins: Style.spacing.sm
    }
    spacing: Style.spacing.xs

    Text {
      text: root.label
      color: root.foreground
      font.family: root.fontFamily
      font.pixelSize: Style.font.caption
      font.bold: true
    }

    Row {
      width: parent.width
      spacing: Style.spacing.xs

      TextField {
        id: field
        width: parent.width - (okBtn.width + cancelBtn.width + Style.spacing.xs * 2)
        onAccepted: root.submitted(text)
      }

      Button {
        id: okBtn
        text: "OK"
        fontFamily: root.fontFamily
        onClicked: root.submitted(field.text)
      }

      Button {
        id: cancelBtn
        text: "Cancel"
        fontFamily: root.fontFamily
        onClicked: root.canceled()
      }
    }
  }
}
