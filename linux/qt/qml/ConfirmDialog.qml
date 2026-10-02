import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: root
    property alias message: messageLabel.text
    property string confirmText: "Confirm"
    property string cancelText: "Cancel"
    property bool destructive: false

    signal confirmed()
    signal canceled()

    modal: true
    focus: true
    anchors.centerIn: parent
    width: Math.min(parent.width - 32, 420)
    title: "Confirmation"
    standardButtons: Dialog.NoButton

    ColumnLayout {
        anchors.fill: parent
        spacing: 16

        Label {
            id: messageLabel
            textFormat: Text.PlainText
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            font.pixelSize: 14
        }

        RowLayout {
            Layout.alignment: Qt.AlignRight
            spacing: 8

            Button {
                text: root.cancelText
                onClicked: {
                    root.canceled()
                    root.close()
                }
            }

            Button {
                text: root.confirmText
                highlighted: true
                onClicked: {
                    root.confirmed()
                    root.close()
                }
            }
        }
    }
}
