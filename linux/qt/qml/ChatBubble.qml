import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: root
    property var msg: null
    property bool isSender: msg ? !!msg.isSender : false
    property var fileObj: (msg && msg.file) ? msg.file : null

    width: parent ? parent.width : 400
    implicitHeight: bubbleFrame.implicitHeight + 8

    Rectangle {
        id: bubbleFrame
        width: Math.min(root.width * 0.78, contentCol.implicitWidth + 24)
        implicitHeight: contentCol.implicitHeight + 16
        radius: 12

        anchors {
            right: root.isSender ? parent.right : undefined
            left: root.isSender ? undefined : parent.left
            rightMargin: root.isSender ? 12 : 0
            leftMargin: root.isSender ? 0 : 12
        }

        color: root.isSender ? (palette.accent ? Qt.tint(palette.base, Qt.rgba(0.2, 0.45, 0.9, 0.25)) : "#e3f2fd")
                            : (palette.base ? Qt.tint(palette.base, Qt.rgba(0.5, 0.5, 0.5, 0.1)) : "#f5f5f5")
        border.color: root.isSender ? "#90caf9" : "#e0e0e0"
        border.width: 1

        ColumnLayout {
            id: contentCol
            anchors.fill: parent
            anchors.margins: 10
            spacing: 6

            // File Card if file message
            ColumnLayout {
                visible: root.fileObj !== null
                Layout.fillWidth: true
                spacing: 4

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8

                    Label {
                        text: "📁"
                        font.pixelSize: 22
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2

                        Label {
                            text: root.fileObj ? (root.fileObj.fileName || "File") : ""
                            textFormat: Text.PlainText
                            font.bold: true
                            font.pixelSize: 13
                            elide: Text.ElideMiddle
                            Layout.fillWidth: true
                        }

                        Label {
                            text: root.fileObj ? (client.formatBytes(root.fileObj.fileSize) + " · " + (root.fileObj.status || "")) : ""
                            textFormat: Text.PlainText
                            font.pixelSize: 11
                            color: "#666"
                        }
                    }
                }

                RowLayout {
                    Layout.alignment: Qt.AlignRight
                    spacing: 6

                    Button {
                        text: "Open File"
                        visible: root.fileObj && root.fileObj.filePath && root.fileObj.filePath.length > 0 && root.fileObj.status === "COMPLETED"
                        font.pixelSize: 11
                        onClicked: client.openLocalFile(root.fileObj.filePath)
                    }

                    Button {
                        text: "Open Folder"
                        visible: root.fileObj && root.fileObj.filePath && root.fileObj.filePath.length > 0 && root.fileObj.status === "COMPLETED"
                        font.pixelSize: 11
                        onClicked: client.revealLocalFile(root.fileObj.filePath)
                    }

                    Button {
                        text: "Retry"
                        visible: root.isSender && root.msg && root.msg.fileTransferId && (root.msg.deliveryStatus === "FAILED" || (root.fileObj && root.fileObj.status === "FAILED"))
                        font.pixelSize: 11
                        highlighted: true
                        onClicked: client.retryTransfer(root.msg.fileTransferId)
                    }
                }
            }

            // Text message content
            TextEdit {
                id: textContent
                visible: root.msg && root.msg.content && root.msg.content.length > 0
                text: root.msg ? root.msg.content : ""
                textFormat: TextEdit.PlainText
                readOnly: true
                selectByMouse: true
                wrapMode: TextEdit.Wrap
                Layout.fillWidth: true
                font.pixelSize: 13
                color: palette.text
            }

            // Message footer (time, status, copy)
            RowLayout {
                Layout.alignment: Qt.AlignRight
                spacing: 6

                ToolButton {
                    text: "📋"
                    visible: root.msg && root.msg.content && root.msg.content.length > 0
                    font.pixelSize: 11
                    ToolTip.visible: hovered
                    ToolTip.text: "Copy message"
                    onClicked: client.copyToClipboard(root.msg.content)
                }

                Label {
                    text: root.msg ? client.formatTimestampTime(root.msg.timestamp) : ""
                    font.pixelSize: 10
                    color: "#777"
                }

                Label {
                    visible: root.isSender && root.msg
                    text: {
                        if (!root.msg) return ""
                        var st = root.msg.deliveryStatus
                        if (st === "DELIVERED") return "✓✓"
                        if (st === "SENT") return "✓"
                        if (st === "FAILED") return "⚠ Failed"
                        return "…"
                    }
                    font.pixelSize: 10
                    color: root.msg && root.msg.deliveryStatus === "FAILED" ? "#d32f2f" : "#777"
                }
            }
        }
    }
}
