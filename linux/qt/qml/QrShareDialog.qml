import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: root
    modal: true
    focus: true
    anchors.centerIn: parent
    width: Math.min(parent.width - 32, 380)
    title: "QR Code File Sharing"
    standardButtons: Dialog.NoButton

    property var qrResult: client.activeQrShareResult
    property var qrRows: (qrResult && qrResult.qr) ? qrResult.qr : []
    property string shareUrl: (qrResult && qrResult.url) ? qrResult.url : ""
    property real expiresAt: (qrResult && qrResult.expiresAt) ? qrResult.expiresAt : 0

    onClosed: {
        if (client.qrShareActive) {
            client.stopQrShare()
        }
    }

    Connections {
        target: client
        function onQrShareResultChanged() {
            if (!client.qrShareActive && root.visible) {
                root.close()
            }
        }
        function onStateUpdated() {
            var daemonActive = client.qrShare && client.qrShare.active === true
            if (!daemonActive && root.visible && client.activeQrShareResult && client.activeQrShareResult.qr) {
                root.close()
            }
        }
        function onSessionReset() {
            if (root.visible) {
                root.close()
            }
        }
    }

    Timer {
        id: countdownTimer
        interval: 1000
        repeat: true
        running: root.visible && root.expiresAt > 0
        property real now: Date.now()
        onTriggered: {
            now = Date.now()
            if (root.expiresAt > 0 && now >= root.expiresAt) {
                root.close()
            }
        }
    }

    readonly property int secondsLeft: root.expiresAt > 0
        ? Math.max(0, Math.round((root.expiresAt - countdownTimer.now) / 1000))
        : 0

    ColumnLayout {
        anchors.fill: parent
        spacing: 12

        Label {
            text: "Scan with another device on the same Wi-Fi network to download files."
            wrapMode: Text.Wrap
            Layout.fillWidth: true
            font.pixelSize: 13
        }

        Rectangle {
            id: qrContainer
            Layout.alignment: Qt.AlignHCenter
            width: 256
            height: 256
            color: "white"
            border.color: "#ccc"
            border.width: 1
            visible: root.qrRows.length > 0

            Canvas {
                id: qrCanvas
                anchors.fill: parent
                renderTarget: Canvas.FramebufferObject

                onPaint: {
                    var ctx = getContext("2d")
                    ctx.fillStyle = "white"
                    ctx.fillRect(0, 0, width, height)

                    var rows = root.qrRows
                    var n = rows.length
                    if (n === 0) return

                    var minCanvasDimension = Math.min(width, height)
                    var cell = Math.floor(minCanvasDimension / (n + 8))
                    if (cell < 1) cell = 1

                    var matrixSize = n * cell
                    var offsetX = Math.floor((width - matrixSize) / 2)
                    var offsetY = Math.floor((height - matrixSize) / 2)

                    ctx.fillStyle = "black"
                    for (var r = 0; r < n; r++) {
                        var row = rows[r]
                        for (var c = 0; c < row.length; c++) {
                            if (row.charAt(c) === '1') {
                                ctx.fillRect(offsetX + c * cell, offsetY + r * cell, cell, cell)
                            }
                        }
                    }
                }

                Connections {
                    target: root
                    function onQrRowsChanged() {
                        qrCanvas.requestPaint()
                    }
                }
            }
        }

        Label {
            visible: root.qrRows.length === 0
            text: "Starting QR share..."
            textFormat: Text.PlainText
            Layout.alignment: Qt.AlignHCenter
            font.italic: true
        }

        RowLayout {
            Layout.fillWidth: true
            visible: root.shareUrl.length > 0
            spacing: 8

            TextField {
                text: root.shareUrl
                readOnly: true
                Layout.fillWidth: true
                font.pixelSize: 12
                selectByMouse: true
            }

            Button {
                text: "Copy"
                onClicked: client.copyToClipboard(root.shareUrl)
            }
        }

        Label {
            visible: root.secondsLeft > 0
            text: "Link expires in " + Math.floor(root.secondsLeft / 3600) + "h " + Math.floor((root.secondsLeft % 3600) / 60) + "m " + (root.secondsLeft % 60) + "s"
            textFormat: Text.PlainText
            Layout.alignment: Qt.AlignHCenter
            font.pixelSize: 12
            color: "#666"
        }

        RowLayout {
            Layout.alignment: Qt.AlignRight
            spacing: 8

            Button {
                text: "Stop Sharing"
                highlighted: true
                onClicked: {
                    client.stopQrShare()
                    root.close()
                }
            }

            Button {
                text: "Close"
                onClicked: root.close()
            }
        }
    }
}
