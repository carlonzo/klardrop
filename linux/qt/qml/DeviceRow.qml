import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ItemDelegate {
    id: root
    property var device: null
    property bool isTrusted: device ? device.trustStatus === "trusted" : false
    property bool isSelected: isTrusted && client.selectedDeviceId === (device ? device.deviceId : "")

    signal unpairRequested(var device)

    width: parent ? parent.width : 280
    height: 52
    highlighted: root.isSelected

    contentItem: RowLayout {
        spacing: 10

        // Device Icon
        Label {
            text: {
                if (!root.device) return "💻"
                var t = String(root.device.deviceType || "").toUpperCase()
                if (t === "MOBILE") return "📱"
                if (t === "DESKTOP") return "💻"
                return "📡"
            }
            font.pixelSize: 18
            Layout.alignment: Qt.AlignVCenter
        }

        // Names & Status
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            RowLayout {
                Layout.fillWidth: true
                spacing: 6

                Label {
                    text: root.device ? (root.device.deviceName || root.device.deviceId) : ""
                    textFormat: Text.PlainText
                    font.bold: root.isSelected || (root.device && root.device.hasUnread)
                    font.pixelSize: 13
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }

                // Unread Count Badge
                Rectangle {
                    visible: root.isTrusted && root.device && root.device.unreadCount > 0
                    width: Math.max(18, unreadLabel.implicitWidth + 8)
                    height: 18
                    radius: 9
                    color: "#e53935"

                    Label {
                        id: unreadLabel
                        anchors.centerIn: parent
                        text: root.device ? String(root.device.unreadCount) : "0"
                        color: "white"
                        font.pixelSize: 10
                        font.bold: true
                    }
                }
            }

            RowLayout {
                spacing: 4

                // Reachability Indicator dot
                Rectangle {
                    width: 7
                    height: 7
                    radius: 3.5
                    color: {
                        if (!root.device) return "#9e9e9e"
                        var r = root.device.reachability
                        if (r === "reachable") return "#4caf50"
                        if (r === "probing") return "#ff9800"
                        return "#9e9e9e"
                    }
                }

                Label {
                    text: {
                        if (!root.device) return ""
                        var r = root.device.reachability || "unknown"
                        return r.charAt(0).toUpperCase() + r.slice(1)
                    }
                    font.pixelSize: 11
                    color: "#777"
                }
            }
        }

        // Action buttons
        Button {
            visible: !root.isTrusted
            text: "Pair"
            font.pixelSize: 11
            Layout.alignment: Qt.AlignVCenter
            onClicked: {
                if (root.device) client.pair(root.device.deviceId)
            }
        }

        ToolButton {
            visible: root.isTrusted
            text: "✕"
            font.pixelSize: 12
            ToolTip.visible: hovered
            ToolTip.text: "Forget device"
            Layout.alignment: Qt.AlignVCenter
            onClicked: {
                if (root.device) root.unpairRequested(root.device)
            }
        }
    }

    onClicked: {
        if (root.isTrusted && root.device) {
            client.selectDevice(root.device.deviceId, root.device.deviceName)
        }
    }
}
