import QtQuick
import QtQuick.Controls.impl

IconImage {
    id: root
    property int size: 18
    property color tint: palette.windowText
    width: size
    height: size
    sourceSize.width: size
    sourceSize.height: size
    fillMode: Image.PreserveAspectFit
    color: tint
}
