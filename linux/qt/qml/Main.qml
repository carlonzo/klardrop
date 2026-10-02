import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window

ApplicationWindow {
    id: window
    visible: true
    width: 1000
    height: 700
    minimumWidth: 760
    minimumHeight: 480
    title: "Klardrop"

    onActiveChanged: client.setWindowActive(active && visible)
    onVisibleChanged: client.setWindowActive(active && visible)

    property var unpairTargetDevice: null

    function sendMessage() {
        if (!client.connected || client.sendingText) return
        var draft = messageField.text
        if (draft.trim().length > 0) {
            client.sendText(client.selectedDeviceId, draft)
        }
    }

    Connections {
        target: client
        function onSessionReset() {
            renameDialog.close()
            unpairDialog.close()
            qrShareDialog.close()
            window.unpairTargetDevice = null
            messageField.text = ""
        }
        function onTextSent(deviceId, text) {
            if (client.selectedDeviceId === deviceId) {
                if (messageField.text === text) {
                    messageField.text = ""
                }
            }
        }
    }

    // Top Banners & Alerts
    header: ToolBar {
        visible: !client.connected || client.lastError.length > 0 || (client.hasPairingDialog && client.pairingDialog.isError) || client.incomingRequests.length > 0 || client.notifications.length > 0 || (updateBannerRow && updateBannerRow.hasUpdate && updateBannerRow.dismissedVersion !== updateBannerRow.currentVerKey)
        background: Rectangle {
            color: !client.connected ? "#fff3e0" : (client.lastError.length > 0 ? "#ffebee" : "#e8f5e9")
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 4
            spacing: 4

            // Offline banner
            RowLayout {
                visible: !client.connected
                Layout.fillWidth: true
                spacing: 8

                Label {
                    text: "⚠ " + client.connectionStatus
                    textFormat: Text.PlainText
                    font.bold: true
                    color: "#e65100"
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                }

                Button {
                    text: "Retry Now"
                    font.pixelSize: 11
                    onClicked: client.checkControlFileNow()
                }
            }

            // Update Banner
            RowLayout {
                id: updateBannerRow
                objectName: "updateBannerRow"
                property var updateInfo: client.update
                property string updateStatus: updateInfo ? (updateInfo.status || "") : ""
                property bool hasUpdate: ["available", "downloading", "ready", "applying", "failed"].indexOf(updateStatus) >= 0
                property string dismissedVersion: ""
                property string currentVerKey: updateInfo && updateInfo.version ? updateInfo.version : ("err:" + (updateInfo ? (updateInfo.error || "") : ""))

                visible: hasUpdate && dismissedVersion !== currentVerKey
                Layout.fillWidth: true
                spacing: 8

                Label {
                    id: updateBannerText
                    objectName: "updateBannerText"
                    text: {
                        if (!updateBannerRow.updateInfo) return ""
                        var ver = updateBannerRow.updateInfo.version || ""
                        if (updateBannerRow.updateStatus === "ready") {
                            return "Update " + (ver ? ver + " " : "") + "downloaded — apply to restart daemon. (Quit and reopen to load updated UI)"
                        } else if (updateBannerRow.updateStatus === "applying") {
                            return "Restarting daemon… Quit and reopen to load updated UI."
                        } else if (updateBannerRow.updateStatus === "downloading") {
                            var frac = updateBannerRow.updateInfo.fraction
                            return "Downloading update" + (typeof frac === "number" && frac >= 0 ? (" " + Math.round(frac * 100) + "%") : "…")
                        } else if (updateBannerRow.updateStatus === "failed") {
                            return "Update failed: " + (updateBannerRow.updateInfo.error || "Unknown error")
                        } else {
                            var act = updateBannerRow.updateInfo.action
                            if (act && act.type === "command") {
                                return "Update available " + (ver ? "(" + ver + "): " : ": ") + act.value
                            }
                            return "Update available: " + (ver || "new version")
                        }
                    }
                    textFormat: Text.PlainText
                    font.bold: updateBannerRow.updateStatus === "ready"
                    color: updateBannerRow.updateStatus === "failed" ? "#c62828" : (updateBannerRow.updateStatus === "ready" ? "#2e7d32" : "#1565c0")
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                }

                Button {
                    visible: !!(updateBannerRow.updateInfo && updateBannerRow.updateInfo.notesUrl)
                    text: "Release Notes"
                    font.pixelSize: 11
                    onClicked: client.openUrl(updateBannerRow.updateInfo.notesUrl)
                }

                Button {
                    objectName: "applyUpdateButton"
                    visible: updateBannerRow.updateStatus === "ready"
                    text: "Apply & Restart"
                    font.pixelSize: 11
                    onClicked: client.applyUpdate()
                }

                Button {
                    visible: updateBannerRow.updateStatus === "failed"
                    text: "Check Again"
                    font.pixelSize: 11
                    onClicked: client.checkUpdate()
                }

                Button {
                    visible: !!(updateBannerRow.updateStatus === "available" && updateBannerRow.updateInfo.action && updateBannerRow.updateInfo.action.type === "command")
                    text: "Copy Command"
                    font.pixelSize: 11
                    onClicked: client.copyToClipboard(updateBannerRow.updateInfo.action.value)
                }

                Button {
                    visible: !!(updateBannerRow.updateStatus === "available" && updateBannerRow.updateInfo.action && updateBannerRow.updateInfo.action.type === "url")
                    text: "Download"
                    font.pixelSize: 11
                    onClicked: client.openUrl(updateBannerRow.updateInfo.action.value)
                }

                ToolButton {
                    text: "✕"
                    font.pixelSize: 11
                    onClicked: updateBannerRow.dismissedVersion = updateBannerRow.currentVerKey
                }
            }

            // Action Error banner
            RowLayout {
                visible: client.lastError.length > 0
                Layout.fillWidth: true
                spacing: 8

                Label {
                    text: "Error: " + client.lastError
                    textFormat: Text.PlainText
                    color: "#c62828"
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                }

                ToolButton {
                    text: "✕"
                    font.pixelSize: 11
                    onClicked: client.dismissError()
                }
            }

            // Pairing Error banner
            RowLayout {
                visible: client.hasPairingDialog && client.pairingDialog.isError
                Layout.fillWidth: true
                spacing: 8

                Label {
                    text: "Pairing failed: " + (client.pairingDialog.errorMessage || "Unknown error")
                    textFormat: Text.PlainText
                    color: "#c62828"
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                }

                Button {
                    text: "Dismiss"
                    font.pixelSize: 11
                    onClicked: client.dismissPairingDialog()
                }
            }

            // Notifications repeater (PeerRevokedTrust etc.)
            Repeater {
                model: client.notifications

                RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 8

                    Label {
                        text: (modelData.deviceName || modelData.deviceId || "A device") + " no longer trusts this device"
                        textFormat: Text.PlainText
                        font.bold: true
                        color: "#c62828"
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                    }

                    Button {
                        text: "Pair Again"
                        font.pixelSize: 11
                        highlighted: true
                        onClicked: client.pairFromNotification(modelData.id)
                    }

                    Button {
                        text: "Dismiss"
                        font.pixelSize: 11
                        onClicked: client.dismissNotification(modelData.id)
                    }
                }
            }

            // Incoming requests repeater
            Repeater {
                model: client.incomingRequests

                RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 8

                    readonly property string incomingStatus: modelData.status || (modelData.pendingAuth ? "PendingAuthorization" : "Started")
                    readonly property bool isPending: !!modelData.pendingAuth || incomingStatus === "PendingAuthorization"
                    readonly property bool isCompleted: incomingStatus === "Completed"
                    readonly property bool isFailed: incomingStatus === "Failed" || incomingStatus === "Rejected"

                    Label {
                        text: {
                            var name = modelData.deviceName || modelData.deviceId || "Device"
                            var details = ""
                            if (modelData.fileCount > 0) {
                                details = " (" + modelData.fileCount + (modelData.fileCount === 1 ? " file" : " files")
                                if (modelData.totalSize > 0) {
                                    details += ", " + client.formatBytes(modelData.totalSize)
                                }
                                details += ")"
                            } else if (modelData.text) {
                                details = " (\"" + modelData.text + "\")"
                            }

                            if (isPending) {
                                return "Incoming request from " + name + details
                            }
                            if (isCompleted) {
                                return "Received transfer from " + name + details + " [Completed]"
                            }
                            if (isFailed) {
                                return "Transfer failed from " + name + details + " [" + incomingStatus + "]"
                            }
                            return "Transferring from " + name + details + " [" + incomingStatus + "]"
                        }
                        textFormat: Text.PlainText
                        font.bold: true
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                    }

                    Button {
                        visible: isPending
                        text: "Accept"
                        highlighted: true
                        font.pixelSize: 11
                        onClicked: client.acceptIncoming(modelData.receiveId, modelData.deviceId || "")
                    }

                    Button {
                        visible: isPending
                        text: "Decline"
                        font.pixelSize: 11
                        onClicked: client.rejectIncoming(modelData.receiveId)
                    }

                    Button {
                        visible: isCompleted
                        text: "Open"
                        highlighted: true
                        font.pixelSize: 11
                        onClicked: client.openIncoming(modelData.receiveId)
                    }

                    Button {
                        visible: isCompleted || isFailed
                        text: "Dismiss"
                        font.pixelSize: 11
                        onClicked: client.dismissIncoming(modelData.receiveId)
                    }
                }
            }
        }
    }

    // Main Two-Pane Split
    SplitView {
        anchors.fill: parent
        orientation: Qt.Horizontal

        // Left Pane (Device lists & settings)
        ScrollView {
            id: leftScrollView
            SplitView.preferredWidth: 320
            SplitView.minimumWidth: 260
            SplitView.maximumWidth: 450
            clip: true
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            ColumnLayout {
                width: leftScrollView.availableWidth
                spacing: 12

                // Self Device Card
                Frame {
                    Layout.fillWidth: true
                    Layout.margins: 8
                    padding: 10

                    ColumnLayout {
                        anchors.fill: parent
                        spacing: 8

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8

                            Label {
                                text: "💻"
                                font.pixelSize: 24
                            }

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Label {
                                    text: client.selfDevice.deviceName || "This Device"
                                    textFormat: Text.PlainText
                                    font.bold: true
                                    font.pixelSize: 14
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }

                                Label {
                                    text: "ID: " + (client.selfDevice.deviceId || "—")
                                    textFormat: Text.PlainText
                                    font.pixelSize: 11
                                    color: "#666"
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }

                            ToolButton {
                                text: "✏"
                                font.pixelSize: 12
                                ToolTip.visible: hovered
                                ToolTip.text: "Rename device"
                                onClicked: {
                                    renameInput.text = client.selfDevice.deviceName || ""
                                    renameDialog.open()
                                }
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true

                            Label {
                                text: "Discovery"
                                font.pixelSize: 12
                                Layout.fillWidth: true
                            }

                            Switch {
                                checked: client.backgroundDiscoveryEnabled
                                enabled: client.supportsBackgroundDiscovery && client.connected
                                ToolTip.visible: hovered
                                ToolTip.text: client.supportsBackgroundDiscovery
                                    ? (checked ? "Background discovery is active" : "Background discovery is off")
                                    : "Background discovery not supported on this platform"
                                onToggled: client.setBackgroundDiscovery(checked)
                            }
                        }

                        Button {
                            Layout.fillWidth: true
                            text: "Share Files via QR Code"
                            enabled: client.connected
                            onClicked: {
                                var files = client.pickFilesForQrShare()
                                if (files && files.length > 0) {
                                    qrShareDialog.open()
                                }
                            }
                        }

                        Button {
                            Layout.fillWidth: true
                            text: "Check for Updates"
                            enabled: client.connected
                            onClicked: client.checkUpdate()
                        }
                    }
                }

                // Trusted Devices Section
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    Label {
                        text: "TRUSTED DEVICES (" + client.pairedDevices.length + ")"
                        font.bold: true
                        font.pixelSize: 11
                        color: "#777"
                        Layout.leftMargin: 12
                    }

                    Label {
                        visible: client.pairedDevices.length === 0
                        text: "No paired devices yet."
                        font.italic: true
                        font.pixelSize: 12
                        color: "#888"
                        Layout.leftMargin: 16
                    }

                    Repeater {
                        model: client.pairedDevices

                        DeviceRow {
                            required property var modelData
                            device: modelData
                            Layout.fillWidth: true
                            onUnpairRequested: function(dev) {
                                window.unpairTargetDevice = dev
                                unpairDialog.message = "Forget " + (dev.deviceName || dev.deviceId) + "? You will need to pair again to reconnect."
                                unpairDialog.open()
                            }
                        }
                    }
                }

                // Nearby Devices Section
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    Label {
                        text: "NEARBY DEVICES (" + client.nearbyDevices.length + ")"
                        font.bold: true
                        font.pixelSize: 11
                        color: "#777"
                        Layout.leftMargin: 12
                    }

                    Label {
                        visible: client.nearbyDevices.length === 0
                        text: client.connected ? "Scanning for nearby devices…" : "Disconnected"
                        font.italic: true
                        font.pixelSize: 12
                        color: "#888"
                        Layout.leftMargin: 16
                    }

                    Repeater {
                        model: client.nearbyDevices

                        DeviceRow {
                            required property var modelData
                            device: modelData
                            Layout.fillWidth: true
                        }
                    }
                }
            }
        }

        // Right Pane (Chat / Transfer Pane)
        Item {
            SplitView.fillWidth: true

            // Empty Selection State
            ColumnLayout {
                anchors.centerIn: parent
                visible: client.selectedDeviceId.length === 0
                spacing: 12

                Label {
                    text: "💬"
                    font.pixelSize: 48
                    Layout.alignment: Qt.AlignHCenter
                }

                Label {
                    text: "Select a Device"
                    font.bold: true
                    font.pixelSize: 18
                    Layout.alignment: Qt.AlignHCenter
                }

                Label {
                    text: "Choose a trusted device from the list on the left to view messages and transfer files."
                    font.pixelSize: 13
                    color: "#777"
                    wrapMode: Text.Wrap
                    Layout.maximumWidth: 320
                    horizontalAlignment: Text.AlignHCenter
                }
            }

            // Selected Device Chat View
            ColumnLayout {
                anchors.fill: parent
                visible: client.selectedDeviceId.length > 0
                spacing: 0

                // Chat Header
                ToolBar {
                    Layout.fillWidth: true

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 12

                        Label {
                            text: client.selectedDeviceName
                            textFormat: Text.PlainText
                            font.bold: true
                            font.pixelSize: 15
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                        }

                        ToolButton {
                            text: "🔄"
                            ToolTip.visible: hovered
                            ToolTip.text: "Refresh History"
                            onClicked: client.refreshHistory()
                        }

                        Button {
                            text: "Send Files"
                            font.pixelSize: 12
                            enabled: client.connected
                            onClicked: client.pickFilesToSend(client.selectedDeviceId)
                        }

                        Button {
                            text: "Send Clipboard"
                            font.pixelSize: 12
                            enabled: client.connected
                            onClicked: client.sendClipboard(client.selectedDeviceId)
                        }

                        ToolButton {
                            text: "✕"
                            ToolTip.visible: hovered
                            ToolTip.text: "Close Chat"
                            onClicked: client.clearSelection()
                        }
                    }
                }

                // In-flight Transfers for this device
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.margins: 8
                    spacing: 4

                    Repeater {
                        model: {
                            var list = []
                            for (var i = 0; i < client.transfers.length; i++) {
                                var t = client.transfers[i]
                                if (t.deviceId === client.selectedDeviceId) {
                                    list.push(t)
                                }
                            }
                            return list
                        }

                        TransferItem {
                            required property var modelData
                            transferData: modelData
                            Layout.fillWidth: true
                        }
                    }
                }

                // Chat History
                ListView {
                    id: historyList
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    spacing: 8
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                    model: client.historyMessages

                    // Older messages paging header
                    header: Item {
                        width: historyList.width
                        height: client.hasOlderHistory ? 40 : 0
                        visible: client.hasOlderHistory

                        Button {
                            anchors.centerIn: parent
                            text: client.loadingOlder ? "Loading older messages…" : "Load older messages"
                            enabled: !client.loadingOlder
                            font.pixelSize: 11
                            onClicked: client.loadOlderHistory()
                        }
                    }

                    delegate: ColumnLayout {
                        id: delegateCol
                        required property var modelData
                        required property int index
                        width: historyList.width
                        spacing: 6

                        // Day divider header
                        readonly property var prevMsg: index > 0 ? client.historyMessages[index - 1] : null
                        readonly property bool showDayHeader: prevMsg === null ||
                            client.dayKey(prevMsg.timestamp) !== client.dayKey(modelData.timestamp)

                        Rectangle {
                            visible: delegateCol.showDayHeader
                            Layout.alignment: Qt.AlignHCenter
                            Layout.topMargin: 4
                            Layout.bottomMargin: 4
                            width: dayText.implicitWidth + 16
                            height: dayText.implicitHeight + 6
                            radius: 10
                            color: "#e0e0e0"

                            Label {
                                id: dayText
                                anchors.centerIn: parent
                                text: client.formatTimestampDate(delegateCol.modelData.timestamp)
                                font.pixelSize: 11
                                font.bold: true
                                color: "#555"
                            }
                        }

                        ChatBubble {
                            msg: delegateCol.modelData
                            Layout.fillWidth: true
                        }
                    }

                    onCountChanged: {
                        // Scroll to bottom on new messages if near bottom
                        if (atYEnd || count <= 10) {
                            positionViewAtEnd()
                        }
                    }
                }

                // Composer Bar
                Pane {
                    Layout.fillWidth: true
                    padding: 8

                    RowLayout {
                        anchors.fill: parent
                        spacing: 8

                        TextField {
                            id: messageField
                            objectName: "messageField"
                            Layout.fillWidth: true
                            placeholderText: "Type a message…"
                            enabled: client.connected
                            onAccepted: window.sendMessage()
                        }

                        Button {
                            id: sendButton
                            objectName: "sendButton"
                            text: client.sendingText ? "Sending…" : "Send"
                            highlighted: true
                            enabled: client.connected && !client.sendingText && messageField.text.trim().length > 0
                            onClicked: window.sendMessage()
                        }

                        ToolButton {
                            text: "📎"
                            font.pixelSize: 16
                            ToolTip.visible: hovered
                            ToolTip.text: "Send file"
                            enabled: client.connected
                            onClicked: client.pickFilesToSend(client.selectedDeviceId)
                        }

                        ToolButton {
                            text: "📋"
                            font.pixelSize: 16
                            ToolTip.visible: hovered
                            ToolTip.text: "Send clipboard"
                            enabled: client.connected
                            onClicked: client.sendClipboard(client.selectedDeviceId)
                        }
                    }
                }
            }

            // Drag & Drop Area for Files
            DropArea {
                id: chatDropArea
                anchors.fill: parent
                enabled: client.selectedDeviceId.length > 0 && client.connected
                keys: ["text/uri-list"]

                onEntered: dropOverlay.visible = true
                onExited: dropOverlay.visible = false
                onDropped: function(drop) {
                    dropOverlay.visible = false
                    var paths = client.dropUrlsToPaths(drop.urls)
                    if (paths && paths.length > 0) {
                        client.sendFiles(client.selectedDeviceId, paths)
                    }
                }

                Rectangle {
                    id: dropOverlay
                    anchors.fill: parent
                    visible: false
                    color: Qt.rgba(0.13, 0.59, 0.95, 0.2)
                    border.color: "#2196F3"
                    border.width: 3
                    radius: 8

                    ColumnLayout {
                        anchors.centerIn: parent
                        spacing: 8

                        Label {
                            text: "📂"
                            font.pixelSize: 48
                            Layout.alignment: Qt.AlignHCenter
                        }

                        Label {
                            text: "Drop files to send to " + client.selectedDeviceName
                            textFormat: Text.PlainText
                            font.bold: true
                            font.pixelSize: 18
                            color: "#1565C0"
                            Layout.alignment: Qt.AlignHCenter
                        }
                    }
                }
            }
        }
    }

    // Unpair Confirm Dialog
    ConfirmDialog {
        id: unpairDialog
        confirmText: "Forget"
        destructive: true
        onConfirmed: {
            if (window.unpairTargetDevice) {
                client.unpair(window.unpairTargetDevice.deviceId)
                window.unpairTargetDevice = null
            }
        }
        onCanceled: window.unpairTargetDevice = null
    }

    // Incoming Pairing Dialog
    Dialog {
        id: pairRequestDialog
        modal: true
        anchors.centerIn: parent
        width: Math.min(parent.width - 32, 400)
        title: "Pairing Request"
        visible: client.hasPairingDialog && !client.pairingDialog.isError
        standardButtons: Dialog.NoButton

        ColumnLayout {
            anchors.fill: parent
            spacing: 16

            Label {
                text: "Device \"" + (client.hasPairingDialog ? (client.pairingDialog.deviceName || client.pairingDialog.deviceId) : "") + "\" wants to pair with you."
                textFormat: Text.PlainText
                wrapMode: Text.Wrap
                Layout.fillWidth: true
                font.pixelSize: 14
            }

            RowLayout {
                Layout.alignment: Qt.AlignRight
                spacing: 8

                Button {
                    text: "Decline"
                    onClicked: {
                        if (client.hasPairingDialog) {
                            client.rejectPair(client.pairingDialog.deviceId)
                        }
                    }
                }

                Button {
                    text: "Accept"
                    highlighted: true
                    onClicked: {
                        if (client.hasPairingDialog) {
                            client.acceptPair(client.pairingDialog.deviceId)
                        }
                    }
                }
            }
        }
    }

    // Rename Dialog
    Dialog {
        id: renameDialog
        modal: true
        anchors.centerIn: parent
        width: Math.min(parent.width - 32, 380)
        title: "Rename Device"
        standardButtons: Dialog.NoButton

        ColumnLayout {
            anchors.fill: parent
            spacing: 12

            TextField {
                id: renameInput
                Layout.fillWidth: true
                placeholderText: "Enter device name…"
                focus: true
                onAccepted: {
                    if (text.trim().length > 0) {
                        client.renameDevice(text)
                        renameDialog.close()
                    }
                }
            }

            RowLayout {
                Layout.alignment: Qt.AlignRight
                spacing: 8

                Button {
                    text: "Cancel"
                    onClicked: renameDialog.close()
                }

                Button {
                    text: "Save"
                    highlighted: true
                    enabled: renameInput.text.trim().length > 0
                    onClicked: {
                        client.renameDevice(renameInput.text)
                        renameDialog.close()
                    }
                }
            }
        }
    }

    // QR Share Dialog
    QrShareDialog {
        id: qrShareDialog
    }
}
