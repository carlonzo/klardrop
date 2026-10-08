import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Frame {
    id: root
    property var transferData: null

    readonly property string fileName: transferData ? (transferData.fileName || "File") : ""
    readonly property real totalSize: transferData ? Number(transferData.totalSize || 0) : 0
    readonly property real transferredSize: transferData ? Number(transferData.transferredSize || 0) : 0
    readonly property bool isSender: transferData ? !!transferData.isSender : false
    readonly property string phase: transferData ? (transferData.phase || "progress") : "progress"

    Layout.fillWidth: true
    padding: 8

    ColumnLayout {
        anchors.fill: parent
        spacing: 4

        RowLayout {
            Layout.fillWidth: true

            Label {
                text: root.isSender ? "▲ Uploading" : "▼ Downloading"
                font.bold: true
                font.pixelSize: 12
                color: root.isSender ? "#2196F3" : "#4CAF50"
            }

            Label {
                text: root.fileName
                textFormat: Text.PlainText
                Layout.fillWidth: true
                elide: Text.ElideMiddle
                font.bold: true
                font.pixelSize: 13
            }

            Label {
                text: {
                    if (root.phase === "awaiting") return "Waiting for recipient…"
                    if (root.phase === "pending") return "Preparing…"
                    return client.formatBytes(root.transferredSize) + " / " + client.formatBytes(root.totalSize)
                }
                font.pixelSize: 12
                color: "#666"
            }
        }

        ProgressBar {
            Layout.fillWidth: true
            from: 0
            to: root.totalSize > 0 ? root.totalSize : 1
            value: root.transferredSize
            indeterminate: root.phase === "awaiting" || root.phase === "pending"
        }
    }
}
