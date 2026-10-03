import QtQuick
import qs.Commons
import qs.Ui

// Dismissible "update available" banner, the window's equivalent of the JVM
// app's UpdateBanner.kt. Renders nothing unless `update.status` is one of
// available/downloading/ready/applying/failed (mirrors InstallProgress/
// UpdateStatus in compose-ui/.../components/UpdateBanner.kt).
//
// `update` is /state's whole `update{}` object — passed through rather than
// exploded into a dozen properties since it's already the exact shape this
// needs. Purely presentational: every action is a signal, same convention as
// the other shared components.
BorderSurface {
  id: root

  property var update: null

  property color foreground: Color.foreground
  property color accent: Color.accent
  property color urgent: Color.urgent
  property color dim: Qt.darker(foreground, 1.4)
  property string fontFamily: Style.font.family

  signal restartRequested()
  signal copyCommandRequested(string command)
  signal openUrlRequested(string url)
  signal checkRequested()

  // Dismissal is per-version, same as the Compose banner: a newer release
  // (or a fresh failure with no version yet) re-shows it.
  property string dismissedVersion: ""

  readonly property string statusStr: root.update ? (root.update.status || "") : ""
  readonly property bool isActionable: ["available", "downloading", "ready", "applying", "failed"].indexOf(root.statusStr) >= 0
  readonly property string versionKey: root.update && root.update.version ? root.update.version : ("err:" + (root.update ? (root.update.error || "") : ""))

  visible: root.update !== null && root.isActionable && root.versionKey !== root.dismissedVersion
  implicitHeight: content.implicitHeight + Style.spacing.sm * 2
  color: Style.normalFillFor(root.foreground, root.accent)
  borderSpec: Border.controlSpec("normal", root.foreground, root.accent)
  radius: Style.cornerRadius
  padding: Style.spacing.sm

  readonly property var action: root.update ? root.update.action : null
  readonly property real fraction: (root.update && typeof root.update.fraction === "number") ? root.update.fraction : -1

  readonly property string detail: {
    if (!root.update) return ""
    switch (root.statusStr) {
      case "downloading":
        return "Downloading update" + (root.fraction >= 0 ? (" " + Math.round(root.fraction * 100) + "%") : "…")
      case "ready":
        return "Update downloaded — restart to apply."
      case "applying":
        return "Restarting…"
      case "failed":
        return root.update.error || "Update failed."
      default: {
        if (!root.action) return "A new version is ready."
        return root.action.type === "command" ? root.action.value : "A new version is ready to download."
      }
    }
  }

  readonly property string actionLabel: {
    switch (root.statusStr) {
      case "ready": return "Restart"
      case "failed": return "Check again"
      case "downloading":
      case "applying":
        return ""
      default:
        if (!root.action) return ""
        return root.action.type === "command" ? "Copy command" : "Download"
    }
  }

  function runAction() {
    switch (root.statusStr) {
      case "ready":
        root.restartRequested()
        return
      case "failed":
        root.checkRequested()
        return
      default:
        if (root.action && root.action.type === "command") root.copyCommandRequested(root.action.value)
        else if (root.action && root.action.type === "url") root.openUrlRequested(root.action.value)
    }
  }

  Row {
    id: content
    anchors {
      left: parent.left
      right: parent.right
      top: parent.top
    }
    spacing: Style.spacing.sm

    Column {
      width: parent.width - actionCol.width - dismissBtn.width - Style.spacing.sm * 2
      spacing: 1

      Text {
        textFormat: Text.PlainText
        width: parent.width
        text: root.update && root.update.version
          ? ("Update available — " + root.update.version)
          : (root.statusStr === "failed" ? "Update failed" : "Klardrop update")
        color: root.foreground
        font.family: root.fontFamily
        font.pixelSize: Style.font.bodySmall
        font.bold: true
        elide: Text.ElideRight
      }

      Text {
        textFormat: Text.PlainText
        width: parent.width
        text: root.detail
        color: root.statusStr === "failed" ? root.urgent : root.dim
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
        wrapMode: Text.WordWrap
      }

      Text {
        visible: !!(root.update && root.update.notesUrl)
        textFormat: Text.PlainText
        text: "Release notes"
        color: root.accent
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
        font.underline: true

        MouseArea {
          anchors.fill: parent
          cursorShape: Qt.PointingHandCursor
          onClicked: root.openUrlRequested(root.update.notesUrl)
        }
      }
    }

    Column {
      id: actionCol
      anchors.verticalCenter: parent.verticalCenter
      visible: root.actionLabel !== ""
      width: visible ? btn.implicitWidth : 0

      Button {
        id: btn
        text: root.actionLabel
        bordered: true
        fontFamily: root.fontFamily
        fontSize: Style.font.caption
        onClicked: root.runAction()
      }
    }

    PanelActionButton {
      id: dismissBtn
      anchors.verticalCenter: parent.verticalCenter
      iconText: "󰅖"
      tooltipText: "Dismiss"
      fontFamily: root.fontFamily
      onClicked: root.dismissedVersion = root.versionKey
    }
  }
}
