import QtQuick
import qs.Commons
import qs.Ui

// One chat/history bubble: device-to-device text, or a file-transfer
// summary (name + a caller-formatted size/status label) with quick actions
// once the file is on disk. Used by the standalone window's chat pane.
//
// Mostly presentational (bubble layout, delivery/day formatting, URL
// linkification) — the caller still owns every API call via signals, same
// as before, with one exception: `thumbnailNeeded()` fires once per
// created delegate so the caller can lazily fetch a video's poster frame
// (`service.request` reused, not duplicated) and hand the result back
// through `thumbnailState` since the caller (not this item) is what
// survives Repeater rebuilding every delegate on a model refresh.
Item {
  id: root

  property string content: ""
  property bool isSender: false
  property string timestampLabel: ""
  // Raw DeliveryStatus name from /history ("SENDING" | "SENT" | "FAILED");
  // anything else (including "DELIVERED", not currently emitted server-side)
  // falls through to no label.
  property string deliveryStatus: ""
  property string mimeType: ""
  property var messageId: null

  property string fileName: ""
  property string fileMetaLabel: ""
  property string filePath: ""
  // Raw FileTransferStatus name ("IN_PROGRESS" | "COMPLETED" | "FAILED" | "REJECTED").
  property string fileStatus: ""
  property var fileTransferId: null

  // undefined = not requested yet, "pending" = in flight, a string = the
  // PNG path, null = fetched with no thumbnail (ffmpeg missing / error).
  property var thumbnailState: undefined

  property color foreground: Color.foreground
  property color accent: Color.accent
  property color dim: Qt.darker(foreground, 1.4)
  property string fontFamily: Style.font.family

  readonly property bool hasFile: fileName !== ""
  readonly property bool isImageFile: root.mimeType.indexOf("image/") === 0
  readonly property bool isVideoFile: root.mimeType.indexOf("video/") === 0
  readonly property bool canRetryFile: root.isSender && root.hasFile
    && (root.fileStatus === "FAILED" || root.fileStatus === "REJECTED")

  readonly property bool isLongText: root.content.length > 600
  readonly property string displayText: root.isLongText ? root.content.substring(0, 600) : root.content
  readonly property string richContent: root.linkify(root.displayText) + (root.isLongText ? "…" : "")

  readonly property string deliveryLabel: {
    if (!root.isSender) return ""
    switch (root.deliveryStatus) {
      case "SENDING": return "sending"
      case "FAILED": return "failed"
      case "SENT": return "sent"
      case "DELIVERED": return "delivered"
      default: return ""
    }
  }

  signal openFileRequested()
  signal copyTextRequested()
  signal revealRequested()
  signal retryRequested()
  signal showFullTextRequested()
  signal thumbnailNeeded()

  // Escapes quotes too: a peer-controlled message is untrusted input landing
  // inside an href="..." attribute below, so an unescaped `"` or `'` would
  // let a crafted message close the attribute early and inject a second one
  // (e.g. a javascript: or file: href) instead of just rendering as text.
  function escapeHtml(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;")
  }

  // ponytail: escapes first then wraps URL matches in <a>, so a URL
  // containing "&" renders as the (harmless) escaped entity in the href
  // rather than the raw character — fine for click-to-open, not a general
  // URL-in-HTML sanitizer. The char class excludes quotes/angle-brackets so
  // a URL can't smuggle its way out of the href attribute it's placed in.
  function linkify(text) {
    return root.escapeHtml(text).replace(/(https?:\/\/[^\s<>"']+)/g, function(url) {
      return "<a href=\"" + url + "\">" + url + "</a>"
    })
  }

  implicitHeight: bubble.implicitHeight + Style.space(4)

  Component.onCompleted: {
    if (root.hasFile && root.isVideoFile && root.filePath !== "" && root.thumbnailState === undefined) {
      root.thumbnailNeeded()
    }
  }

  BorderSurface {
    id: bubble
    width: Math.min(parent.width * 0.85, msgCol.implicitWidth + Style.space(16))
    implicitHeight: msgCol.implicitHeight + Style.space(16)
    anchors.right: root.isSender ? parent.right : undefined
    anchors.left: root.isSender ? undefined : parent.left
    color: root.isSender
      ? Style.selectedFillFor(root.foreground, root.accent)
      : Style.normalFillFor(root.foreground, root.accent)
    borderSpec: Border.controlSpec("normal", root.foreground, root.accent)
    radius: Style.cornerRadius

    Column {
      id: msgCol
      anchors {
        left: parent.left
        right: parent.right
        top: parent.top
        margins: Style.space(8)
      }
      spacing: Style.space(4)

      // Image preview -------------------------------------------------
      // `sourceSize` only bounds decode memory, not the displayed size —
      // the bounding box below is what actually keeps a huge photo from
      // rendering at native resolution inside the bubble.
      Item {
        visible: root.hasFile && root.isImageFile && root.filePath !== ""
        width: Style.space(220)
        height: Style.space(220)

        Image {
          id: previewImage
          anchors.fill: parent
          source: root.filePath !== "" ? "file://" + root.filePath : ""
          asynchronous: true
          cache: false
          fillMode: Image.PreserveAspectFit
          sourceSize.width: 320
          sourceSize.height: 320
        }

        MouseArea {
          anchors.fill: parent
          cursorShape: Qt.PointingHandCursor
          onClicked: root.openFileRequested()
        }
      }

      // Video preview: poster frame (once fetched) + a play badge, or a
      // plain badge placeholder while pending/unavailable.
      Item {
        visible: root.hasFile && root.isVideoFile && root.filePath !== ""
        width: Style.space(200)
        height: Style.space(120)

        Image {
          anchors.fill: parent
          visible: typeof root.thumbnailState === "string"
          source: (typeof root.thumbnailState === "string") ? "file://" + root.thumbnailState : ""
          asynchronous: true
          cache: false
          fillMode: Image.PreserveAspectFit
          sourceSize.width: 320
          sourceSize.height: 320
        }

        Rectangle {
          anchors.fill: parent
          visible: typeof root.thumbnailState !== "string"
          radius: Style.cornerRadius
          color: Style.normalFillFor(root.foreground, root.accent)
        }

        Rectangle {
          anchors.centerIn: parent
          width: Style.space(36)
          height: width
          radius: width / 2
          color: Qt.rgba(0, 0, 0, 0.5)

          Text {
            anchors.centerIn: parent
            anchors.horizontalCenterOffset: Style.space(1)
            text: "▶"
            color: "white"
            font.family: root.fontFamily
            font.pixelSize: Style.font.icon
          }
        }

        MouseArea {
          anchors.fill: parent
          cursorShape: Qt.PointingHandCursor
          onClicked: root.openFileRequested()
        }
      }

      Row {
        spacing: Style.spacing.xs
        visible: root.hasFile

        Text {
          text: "󰈔"
          font.family: root.fontFamily
          font.pixelSize: Style.font.icon
          color: root.foreground
          anchors.verticalCenter: parent.verticalCenter
        }

        Column {
          spacing: 1
          Text {
            text: root.fileName
            textFormat: Text.PlainText
            font.family: root.fontFamily
            font.pixelSize: Style.font.bodySmall
            font.bold: true
            color: root.foreground
            elide: Text.ElideMiddle
            width: Math.min(Style.space(200), implicitWidth)
          }
          Text {
            text: root.fileMetaLabel
            font.family: root.fontFamily
            font.pixelSize: Style.font.caption
            color: root.dim
          }
        }

        PanelActionButton {
          visible: root.filePath !== ""
          iconText: "󰏌"
          tooltipText: "Open file"
          fontFamily: root.fontFamily
          onClicked: root.openFileRequested()
        }

        PanelActionButton {
          visible: root.filePath !== ""
          iconText: "󰝰"
          tooltipText: "Reveal in folder"
          fontFamily: root.fontFamily
          onClicked: root.revealRequested()
        }

        PanelActionButton {
          visible: root.canRetryFile
          iconText: "󰑓"
          tooltipText: "Retry"
          fontFamily: root.fontFamily
          onClicked: root.retryRequested()
        }
      }

      Row {
        spacing: Style.spacing.xs
        visible: root.content !== "" && (!root.hasFile || root.content !== root.fileName)

        Text {
          textFormat: Text.StyledText
          linkColor: root.accent
          text: root.richContent
          font.family: root.fontFamily
          font.pixelSize: Style.font.bodySmall
          color: root.foreground
          wrapMode: Text.WrapAtWordBoundaryOrAnywhere
          width: Math.min(Style.space(260), implicitWidth)
          // Belt-and-braces: linkify() only ever emits http(s) hrefs, but
          // don't trust that invariant blindly when handing a string to
          // openUrlExternally.
          onLinkActivated: function(link) {
            if (/^https?:\/\//.test(link)) Qt.openUrlExternally(link)
          }
        }

        PanelActionButton {
          iconText: "󰆏"
          tooltipText: "Copy text"
          fontFamily: root.fontFamily
          onClicked: root.copyTextRequested()
        }
      }

      Text {
        visible: root.isLongText
        text: "Show more"
        color: root.accent
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
        font.underline: true

        MouseArea {
          anchors.fill: parent
          cursorShape: Qt.PointingHandCursor
          onClicked: root.showFullTextRequested()
        }
      }

      Text {
        visible: root.timestampLabel !== "" || root.deliveryLabel !== ""
        width: parent.width
        horizontalAlignment: Text.AlignRight
        text: root.timestampLabel + (root.deliveryLabel !== "" ? " · " + root.deliveryLabel : "")
        color: root.dim
        font.family: root.fontFamily
        font.pixelSize: Style.font.caption
      }
    }
  }
}
