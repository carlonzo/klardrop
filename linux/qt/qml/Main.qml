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
    readonly property int bannerCap: 3
    readonly property int bannerTotal: client.notifications.length + client.incomingRequests.length
    readonly property int bannerOverflow: Math.max(0, bannerTotal - bannerCap)

    // Same rules as compose-ui dropOverlayLabel(). Names are rendered as PlainText.
    function dropLabel(urls, deviceName) {
        if (!urls || urls.length === 0) return "Send file to " + deviceName
        if (urls.length > 1) return "Send " + urls.length + " files to " + deviceName
        var name = decodeURIComponent(urls[0].toString().replace(/\/+$/, "").split("/").pop())
        return "Send file " + name + " to " + deviceName
    }

    function sendMessage() {
        if (!client.connected || client.sendingText) return
        var draft = messageField.text
        if (draft.trim().length > 0) {
            client.sendText(client.selectedDeviceId, draft)
        }
    }

    function deviceDisplayName(deviceId, deviceName) {
        var name = deviceName ? String(deviceName) : ""
        if (name.length > 0) return name
        var id = deviceId ? String(deviceId) : ""
        if (id.length > 0) {
            var lists = [client.pairedDevices, client.nearbyDevices]
            for (var li = 0; li < lists.length; li++) {
                var arr = lists[li]
                for (var i = 0; i < arr.length; i++) {
                    if (String(arr[i].deviceId) === id) {
                        var n = arr[i].deviceName ? String(arr[i].deviceName) : ""
                        if (n.length > 0) return n
                    }
                }
            }
        }
        return "A paired device"
    }

    function incomingIsPending(item) {
        if (!item) return false
        var status = item.status || (item.pendingAuth ? "PendingAuthorization" : "Started")
        return !!item.pendingAuth || status === "PendingAuthorization"
    }

    function dismissAllBanners() {
        var i
        var notifIds = []
        for (i = 0; i < client.notifications.length; i++)
            notifIds.push(client.notifications[i].id)
        for (i = 0; i < notifIds.length; i++)
            client.dismissNotification(notifIds[i])
        var recvIds = []
        for (i = 0; i < client.incomingRequests.length; i++) {
            if (!window.incomingIsPending(client.incomingRequests[i]))
                recvIds.push(client.incomingRequests[i].receiveId)
        }
        for (i = 0; i < recvIds.length; i++)
            client.dismissIncoming(recvIds[i])
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

                TintedIcon {
                    source: "qrc:/icons/alert.svg"
                    size: 16
                    tint: "#e65100"
                }

                Label {
                    text: client.connectionStatus
                    textFormat: Text.PlainText
                    font.bold: true
                    color: "#e65100"
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                }

                Button {
                    text: "Retry now"
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
                    text: "Release notes"
                    font.pixelSize: 11
                    onClicked: client.openUrl(updateBannerRow.updateInfo.notesUrl)
                }

                Button {
                    objectName: "applyUpdateButton"
                    visible: updateBannerRow.updateStatus === "ready"
                    text: "Apply & restart"
                    font.pixelSize: 11
                    onClicked: client.applyUpdate()
                }

                Button {
                    visible: updateBannerRow.updateStatus === "failed"
                    text: "Check again"
                    font.pixelSize: 11
                    onClicked: client.checkUpdate()
                }

                Button {
                    visible: !!(updateBannerRow.updateStatus === "available" && updateBannerRow.updateInfo.action && updateBannerRow.updateInfo.action.type === "command")
                    text: "Copy command"
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
                model: Math.min(client.notifications.length, window.bannerCap)

                RowLayout {
                    required property int index
                    readonly property var modelData: client.notifications[index]
                    Layout.fillWidth: true
                    spacing: 8

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0

                        Label {
                            text: window.deviceDisplayName(modelData.deviceId, modelData.deviceName)
                            textFormat: Text.PlainText
                            font.weight: Font.Medium
                            font.pixelSize: 13
                            color: "#c62828"
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                        }

                        Label {
                            text: "no longer trusts this device"
                            textFormat: Text.PlainText
                            font.pixelSize: 12
                            color: "#c62828"
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                        }
                    }

                    Button {
                        text: "Pair again"
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
                model: Math.max(0, Math.min(client.incomingRequests.length, window.bannerCap - Math.min(client.notifications.length, window.bannerCap)))

                RowLayout {
                    required property int index
                    readonly property var modelData: client.incomingRequests[index]
                    Layout.fillWidth: true
                    spacing: 8

                    readonly property string incomingStatus: modelData.status || (modelData.pendingAuth ? "PendingAuthorization" : "Started")
                    readonly property bool isPending: !!modelData.pendingAuth || incomingStatus === "PendingAuthorization"
                    readonly property bool isCompleted: incomingStatus === "Completed"
                    readonly property bool isFailed: incomingStatus === "Failed" || incomingStatus === "Rejected"
                    readonly property string incomingName: window.deviceDisplayName(modelData.deviceId, modelData.deviceName)
                    readonly property string incomingDetails: {
                        if (modelData.fileCount > 0) {
                            var details = modelData.fileCount + (modelData.fileCount === 1 ? " file" : " files")
                            if (modelData.totalSize > 0) {
                                details += ", " + client.formatBytes(modelData.totalSize)
                            }
                            return details
                        }
                        if (modelData.text) return "\"" + modelData.text + "\""
                        return ""
                    }
                    readonly property string incomingTitle: {
                        if (isPending) return "Incoming request"
                        if (isCompleted) return "Received transfer"
                        if (isFailed) return "Transfer failed"
                        return "Transferring"
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0

                        Label {
                            text: incomingTitle + " from " + incomingName
                            textFormat: Text.PlainText
                            font.weight: Font.Medium
                            font.pixelSize: 13
                            color: "#1b5e20"
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                        }

                        Label {
                            visible: incomingDetails.length > 0 || isCompleted || isFailed || (!isPending && incomingStatus.length > 0)
                            text: {
                                var extra = incomingDetails
                                if (isCompleted) extra = extra.length > 0 ? extra + " · Completed" : "Completed"
                                else if (isFailed) extra = extra.length > 0 ? extra + " · " + incomingStatus : incomingStatus
                                else if (!isPending && incomingStatus.length > 0 && extra.length > 0) extra += " · " + incomingStatus
                                else if (!isPending && extra.length === 0) extra = incomingStatus
                                return extra
                            }
                            textFormat: Text.PlainText
                            font.pixelSize: 12
                            color: "#555"
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                        }
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

            RowLayout {
                visible: window.bannerOverflow > 0
                Layout.fillWidth: true
                spacing: 8

                Label {
                    text: window.bannerOverflow + " more"
                    font.pixelSize: 12
                    color: "#555"
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                }

                Button {
                    text: "Dismiss all"
                    font.pixelSize: 11
                    onClicked: window.dismissAllBanners()
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
                    padding: 8

                    ColumnLayout {
                        anchors.fill: parent
                        spacing: 8

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8

                            TintedIcon {
                                source: "qrc:/icons/laptop.svg"
                                size: 24
                            }

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Label {
                                    text: client.selfDevice.deviceName || "This device"
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
                                display: AbstractButton.IconOnly
                                padding: 8
                                ToolTip.visible: hovered
                                ToolTip.text: "Rename device"
                                contentItem: TintedIcon {
                                    source: "qrc:/icons/edit.svg"
                                    size: 16
                                }
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
                            text: "Share files via QR code"
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
                            text: "Check for updates"
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
                        text: "Trusted devices (" + client.pairedDevices.length + ")"
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
                        text: "Nearby devices (" + client.nearbyDevices.length + ")"
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

                TintedIcon {
                    source: "qrc:/icons/chat.svg"
                    size: 48
                    Layout.alignment: Qt.AlignHCenter
                }

                Label {
                    text: "Select a device"
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
                            display: AbstractButton.IconOnly
                            padding: 8
                            ToolTip.visible: hovered
                            ToolTip.text: "Refresh history"
                            contentItem: TintedIcon {
                                source: "qrc:/icons/refresh.svg"
                                size: 16
                            }
                            onClicked: client.refreshHistory()
                        }

                        Button {
                            text: "Send files"
                            font.pixelSize: 12
                            enabled: client.connected
                            onClicked: client.pickFilesToSend(client.selectedDeviceId)
                        }

                        Button {
                            text: "Send clipboard"
                            font.pixelSize: 12
                            enabled: client.connected
                            onClicked: client.sendClipboard(client.selectedDeviceId)
                        }

                        ToolButton {
                            text: "✕"
                            ToolTip.visible: hovered
                            ToolTip.text: "Close chat"
                            onClicked: client.clearSelection()
                        }
                    }
                }

                // In-flight Transfers for this device.
                // A nested layout defaults to Layout.fillHeight, which swallowed
                // the chat pane and left the history list a few pixels tall.
                ColumnLayout {
                    id: transferSection
                    Layout.fillWidth: true
                    Layout.fillHeight: false
                    Layout.topMargin: visible ? 8 : 0
                    Layout.bottomMargin: visible ? 8 : 0
                    spacing: 4
                    visible: transferRepeater.count > 0

                    Repeater {
                        id: transferRepeater
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

                // Chat history fills the leftover height. A nested layout above
                // must not (ColumnLayout defaults to fillHeight and was leaving
                // this list a few pixels tall). The header spacer is applied only
                // after message delegates have a real height, so a short thread
                // sits on the composer and a long one stays pinned to the newest
                // bubble. positionViewAtEnd runs before those heights exist.
                ListView {
                    id: historyList
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.maximumHeight: Number.POSITIVE_INFINITY
                    clip: true
                    spacing: 8
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                    // Stay on the newest messages until the user scrolls upward.
                    property bool followTail: true
                    property real topSpacer: 0
                    property int pinDepth: 0
                    readonly property real olderBarHeight: client.hasOlderHistory ? 40 : 0

                    function pinTail() {
                        if (pinDepth > 3)
                            return
                        pinDepth++
                        // Exclude the spacer itself so this settles in one step
                        // instead of a height ↔ contentHeight binding loop.
                        var messages = Math.max(0, contentHeight - topSpacer - olderBarHeight)
                        var spacer = (count > 0 && messages > 1)
                                ? Math.max(0, height - messages - olderBarHeight) : 0
                        if (Math.abs(spacer - topSpacer) > 0.5)
                            topSpacer = spacer
                        if (followTail)
                            contentY = Math.max(0, contentHeight - height)
                        pinDepth--
                    }

                    onMovementEnded: followTail = atYEnd
                    onMovingChanged: if (moving) followTail = false
                    onCountChanged: if (count <= 10) followTail = true
                    onContentHeightChanged: pinTail()
                    onHeightChanged: pinTail()

                    model: client.historyMessages

                    // Top spacer (empty) plus the "load older" bar. The spacer is
                    // what bottom-anchors a short thread; it stays 0 until the
                    // message delegates have been created and measured.
                    header: Item {
                        width: historyList.width
                        height: historyList.topSpacer + historyList.olderBarHeight

                        Item {
                            width: parent.width
                            height: historyList.olderBarHeight
                            anchors.bottom: parent.bottom
                            visible: client.hasOlderHistory

                            Button {
                                anchors.centerIn: parent
                                text: client.loadingOlder ? "Loading older messages…" : "Load older messages"
                                enabled: !client.loadingOlder
                                font.pixelSize: 11
                                onClicked: client.loadOlderHistory()
                            }
                        }
                    }

                    delegate: ColumnLayout {
                        id: delegateCol
                        required property var modelData
                        required property int index
                        width: historyList.width
                        // ListView reads height, not implicitHeight. A layout root
                        // does not copy implicitHeight into height on its own.
                        height: implicitHeight
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
                            display: AbstractButton.IconOnly
                            padding: 8
                            ToolTip.visible: hovered
                            ToolTip.text: "Send file"
                            enabled: client.connected
                            contentItem: TintedIcon {
                                source: "qrc:/icons/paperclip.svg"
                                size: 16
                            }
                            onClicked: client.pickFilesToSend(client.selectedDeviceId)
                        }

                        ToolButton {
                            display: AbstractButton.IconOnly
                            padding: 8
                            ToolTip.visible: hovered
                            ToolTip.text: "Send clipboard"
                            enabled: client.connected
                            contentItem: TintedIcon {
                                source: "qrc:/icons/clipboard.svg"
                                size: 16
                            }
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

                property string label: ""
                onEntered: function(drag) {
                    label = window.dropLabel(drag.urls, client.selectedDeviceName)
                    dropOverlay.visible = true
                }
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

                        TintedIcon {
                            source: "qrc:/icons/folder.svg"
                            size: 48
                            tint: "#1565C0"
                            Layout.alignment: Qt.AlignHCenter
                        }

                        Label {
                            text: chatDropArea.label
                            elide: Text.ElideMiddle
                            Layout.maximumWidth: chatDropArea.width - 32
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
        title: "Pairing request"
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
        title: "Rename device"
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
