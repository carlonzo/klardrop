#ifndef CLIENTBRIDGE_H
#define CLIENTBRIDGE_H

#include <QObject>
#include <QString>
#include <QStringList>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonValue>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTimer>
#include <QFileSystemWatcher>
#include <QUrl>
#include <QSet>

class ClientBridge : public QObject {
    Q_OBJECT

    // Connection & Control File State
    Q_PROPERTY(bool connected READ isConnected NOTIFY connectionChanged)
    Q_PROPERTY(QString connectionStatus READ connectionStatus NOTIFY connectionChanged)
    Q_PROPERTY(QString controlFilePath READ controlFilePath NOTIFY controlFileChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY errorOccurred)
    Q_PROPERTY(bool windowActive READ isWindowActive WRITE setWindowActive NOTIFY windowActiveChanged)

    // Daemon State
    Q_PROPERTY(QJsonObject selfDevice READ selfDevice NOTIFY stateUpdated)
    Q_PROPERTY(QJsonArray pairedDevices READ pairedDevices NOTIFY stateUpdated)
    Q_PROPERTY(QJsonArray nearbyDevices READ nearbyDevices NOTIFY stateUpdated)
    Q_PROPERTY(QJsonObject pairingDialog READ pairingDialog NOTIFY stateUpdated)
    Q_PROPERTY(bool hasPairingDialog READ hasPairingDialog NOTIFY stateUpdated)
    Q_PROPERTY(QJsonArray incomingRequests READ incomingRequests NOTIFY stateUpdated)
    Q_PROPERTY(QJsonArray transfers READ transfers NOTIFY stateUpdated)
    Q_PROPERTY(QJsonArray notifications READ notifications NOTIFY stateUpdated)
    Q_PROPERTY(QJsonObject qrShare READ qrShare NOTIFY stateUpdated)
    Q_PROPERTY(bool supportsBackgroundDiscovery READ supportsBackgroundDiscovery NOTIFY stateUpdated)
    Q_PROPERTY(bool backgroundDiscoveryEnabled READ backgroundDiscoveryEnabled NOTIFY stateUpdated)

    // Selection & Chat History
    Q_PROPERTY(QString selectedDeviceId READ selectedDeviceId NOTIFY selectedDeviceChanged)
    Q_PROPERTY(QString selectedDeviceName READ selectedDeviceName NOTIFY selectedDeviceChanged)
    Q_PROPERTY(QJsonArray historyMessages READ historyMessages NOTIFY historyChanged)
    Q_PROPERTY(bool loadingHistory READ loadingHistory NOTIFY historyLoadingChanged)
    Q_PROPERTY(bool loadingOlder READ loadingOlder NOTIFY historyLoadingChanged)
    Q_PROPERTY(bool hasOlderHistory READ hasOlderHistory NOTIFY historyChanged)
    Q_PROPERTY(bool sendingText READ isSendingText NOTIFY sendingTextChanged)

    // QR Share Result
    Q_PROPERTY(QJsonObject activeQrShareResult READ activeQrShareResult NOTIFY qrShareResultChanged)
    Q_PROPERTY(bool qrShareActive READ isQrShareActive NOTIFY qrShareResultChanged)

    // Daemon Updates
    Q_PROPERTY(QJsonObject update READ update NOTIFY updateStateChanged)

public:
    explicit ClientBridge(const QString &overrideControlPath = QString(), bool checkRuntimeMode = false, QObject *parent = nullptr);
    ~ClientBridge() override;

    bool isConnected() const { return m_connected; }
    QString connectionStatus() const { return m_connectionStatus; }
    QString controlFilePath() const { return m_controlFilePath; }
    QString lastError() const { return m_lastError; }
    QJsonObject update() const { return m_update; }
    bool isWindowActive() const { return m_windowActive; }

    QJsonObject selfDevice() const { return m_selfDevice; }
    QJsonArray pairedDevices() const { return m_pairedDevices; }
    QJsonArray nearbyDevices() const { return m_nearbyDevices; }
    QJsonObject pairingDialog() const { return m_pairingDialog; }
    bool hasPairingDialog() const { return !m_pairingDialog.isEmpty(); }
    QJsonArray incomingRequests() const { return m_incomingRequests; }
    QJsonArray transfers() const { return m_transfers; }
    QJsonArray notifications() const { return m_notifications; }
    QJsonObject qrShare() const { return m_qrShare; }
    bool supportsBackgroundDiscovery() const { return m_supportsBackgroundDiscovery; }
    bool backgroundDiscoveryEnabled() const { return m_backgroundDiscoveryEnabled; }

    QString selectedDeviceId() const { return m_selectedDeviceId; }
    QString selectedDeviceName() const { return m_selectedDeviceName; }
    QJsonArray historyMessages() const { return m_historyMessages; }
    bool loadingHistory() const { return m_loadingHistory; }
    bool loadingOlder() const { return m_loadingOlder; }
    bool hasOlderHistory() const { return m_hasOlderHistory; }
    bool isSendingText() const { return m_sendingText; }

    QJsonObject activeQrShareResult() const { return m_activeQrShareResult; }
    bool isQrShareActive() const { return !m_activeQrShareResult.isEmpty(); }

    static QString resolveStandardControlPath();
    static bool validatePort(const QJsonValue &portVal);
    static bool validateToken(const QString &token);
    static bool validateControlFile(const QString &path, int &outPort, QString &outToken, QString &outError);

public slots:
    // Window state & lifecycle
    void setWindowActive(bool active);

    // Device Selection & History
    void selectDevice(const QString &deviceId, const QString &deviceName);
    void clearSelection();
    void refreshHistory();
    void loadOlderHistory();

    // Pairing
    void pair(const QString &deviceId);
    void unpair(const QString &deviceId);
    void acceptPair(const QString &deviceId);
    void rejectPair(const QString &deviceId);
    void dismissPairingDialog();

    // Incoming
    void acceptIncoming(int receiveId, const QString &deviceId = QString());
    void rejectIncoming(int receiveId);
    void openIncoming(int receiveId);
    void dismissIncoming(int receiveId);

    // Messaging & Transfers
    void sendText(const QString &deviceId, const QString &text);
    void sendClipboard(const QString &deviceId);
    void sendFiles(const QString &deviceId, const QStringList &paths);
    void retryTransfer(qint64 fileTransferId);

    // QR Share
    void startQrShare(const QStringList &paths);
    void stopQrShare();

    // Settings
    void setBackgroundDiscovery(bool enabled);
    void renameDevice(const QString &newName);

    // Notifications
    void dismissNotification(int id);
    void pairFromNotification(int id);

    // Daemon Updates
    void checkUpdate();
    void applyUpdate();
    void openUrl(const QUrl &url);

    // System & Helpers
    void copyToClipboard(const QString &text);
    void openLocalFile(const QString &path);
    void revealLocalFile(const QString &path);
    QStringList pickFilesToSend(const QString &deviceId);
    QStringList pickFilesForQrShare();
    QStringList dropUrlsToPaths(const QList<QUrl> &urls) const;
    QString cleanLocalFilePath(const QUrl &url) const;

    QString formatBytes(qint64 bytes) const;
    QString formatTimestampTime(qint64 millis) const;
    QString formatTimestampDate(qint64 millis) const;
    QString dayKey(qint64 millis) const;
    void dismissError();
    void checkControlFileNow();

signals:
    void connectionChanged();
    void controlFileChanged();
    void sessionReset();
    void errorOccurred(const QString &message);
    void stateUpdated();
    void selectedDeviceChanged();
    void historyChanged();
    void historyLoadingChanged();
    void qrShareResultChanged();
    void updateStateChanged();
    void windowActiveChanged();
    void sendingTextChanged();
    void textSent(const QString &deviceId, const QString &text);

private slots:
    void onControlFileWatchTriggered(const QString &path);
    void onReconnectTimerFired();
    void onBackoffTimerFired();
    void onStatePollTimeout();

private:
    void setupWatcher();
    void reloadControlFile();
    void handleDisconnect(const QString &reason);
    void setConnectionStatus(bool connected, const QString &status);
    void pollState();
    void onStatePollFinished(QNetworkReply *reply, quint64 sessionGen);
    void sendActionRequest(const QString &method, const QString &path, const QJsonObject &body,
                           const std::function<void(bool success, const QJsonObject &response, const QString &error)> &callback = nullptr);

    void fetchHistoryPage(const QString &deviceId, const QJsonValue &beforeId, bool isOlderPage, quint64 reqSeq);
    void syncLatestMessagesForSelectedDevice(quint64 reqSeq);
    void checkAndMarkRead(const QString &deviceId);
    void markHistoryRead(const QString &deviceId);
    QNetworkRequest createRequest(const QString &path) const;

    // Configuration / paths
    QString m_controlFilePath;
    QString m_overrideControlPath;
    int m_port = 0;
    QString m_token;
    bool m_connected = false;
    QString m_connectionStatus = QStringLiteral("Disconnected");
    QString m_lastError;
    bool m_windowActive = true;

    // Concurrency and Session Generation
    quint64 m_sessionGeneration = 0;
    quint64 m_pollGeneration = 0;
    quint64 m_deviceSelectSeq = 0;
    quint64 m_historySeq = 0;
    quint64 m_latestAppliedHistorySeq = 0;
    int m_inFlightActions = 0;

    static constexpr int MAX_CONCURRENT_ACTIONS = 8;
    static constexpr qint64 MAX_REPLY_BODY_BYTES = 4 * 1024 * 1024; // 4 MB

    // ponytail: loaded history grows with requested pages as the user navigates;
    // upgrade to a bounded memory ceiling/window if excessive growth is observed.

    // State & Version
    qint64 m_stateVersion = 0;
    int m_backoffMs = 1000;
    static constexpr int MIN_BACKOFF_MS = 1000;
    static constexpr int MAX_BACKOFF_MS = 16000;

    // Parsed State
    QJsonObject m_selfDevice;
    QJsonArray m_pairedDevices;
    QJsonArray m_nearbyDevices;
    QJsonObject m_pairingDialog;
    QJsonArray m_incomingRequests;
    QJsonArray m_transfers;
    QJsonArray m_notifications;
    QJsonObject m_qrShare;
    QJsonObject m_update;
    bool m_supportsBackgroundDiscovery = false;
    bool m_backgroundDiscoveryEnabled = true;
    bool m_checkRuntimeMode = false;

    // Active Chat & History
    QString m_selectedDeviceId;
    QString m_selectedDeviceName;
    QJsonArray m_historyMessages; // Chronological (oldest first, newest bottom)
    QJsonValue m_nextBeforeId;
    bool m_hasOlderHistory = false;
    bool m_loadingHistory = false;
    bool m_loadingOlder = false;
    bool m_sendingText = false;
    QSet<QString> m_inFlightReadDevices;

    // QR Share Result
    QJsonObject m_activeQrShareResult;

    // Timers & Network
    QNetworkAccessManager m_nam;
    QNetworkReply *m_pollReply = nullptr;
    QByteArray m_pollBuffer;
    QTimer m_pollTimeoutTimer;
    QTimer m_backoffTimer;
    QTimer m_reconnectTimer;
    QFileSystemWatcher m_fileWatcher;
};

#endif // CLIENTBRIDGE_H
