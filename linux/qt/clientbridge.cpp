#include "clientbridge.h"

#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QGuiApplication>
#include <QClipboard>
#include <QDesktopServices>
#include <QFileDialog>
#include <QDateTime>
#include <QNetworkProxy>
#include <QEventLoop>
#include <QDebug>

QString ClientBridge::resolveStandardControlPath() {
    QByteArray xdg = qgetenv("XDG_RUNTIME_DIR");
    if (!xdg.isEmpty()) {
        return QString::fromUtf8(xdg) + QStringLiteral("/klardrop/control.json");
    }
    QByteArray home = qgetenv("HOME");
    QString homeStr = !home.isEmpty() ? QString::fromUtf8(home) : QStringLiteral(".");
    return homeStr + QStringLiteral("/.cache/klardrop/control.json");
}

bool ClientBridge::validatePort(const QJsonValue &portVal) {
    if (!portVal.isDouble()) return false;
    double d = portVal.toDouble();
    int i = portVal.toInt();
    if (d != static_cast<double>(i)) return false; // Reject floating point numbers
    return i >= 1 && i <= 65535;
}

bool ClientBridge::validateToken(const QString &token) {
    static const QRegularExpression tokenRegex(QRegularExpression::anchoredPattern(QStringLiteral("[A-Za-z0-9_\\-\\.~]+")));
    return !token.isEmpty() && token.length() <= 256 && tokenRegex.match(token).hasMatch();
}

bool ClientBridge::validateControlFile(const QString &path, int &outPort, QString &outToken, QString &outError) {
    outPort = 0;
    outToken.clear();

    QFileInfo fi(path);
    if (!fi.exists()) {
        outError = QStringLiteral("Control file does not exist");
        return false;
    }
    // Reject non-regular file (directory, fifo, socket, etc.)
    if (!fi.isFile() || fi.isDir() || fi.isBundle()) {
        outError = QStringLiteral("Control file is not a regular file");
        return false;
    }

    qint64 size = fi.size();
    if (size <= 0) {
        outError = QStringLiteral("Control file is empty");
        return false;
    }
    if (size > 4096) {
        outError = QStringLiteral("Control file exceeds 4096 bytes");
        return false;
    }

    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        outError = QStringLiteral("Failed to open control file");
        return false;
    }

    QByteArray data = file.read(size);
    file.close();

    if (data.size() != size) {
        outError = QStringLiteral("Failed to read complete control file");
        return false;
    }

    QJsonParseError parseErr;
    QJsonDocument doc = QJsonDocument::fromJson(data, &parseErr);
    if (parseErr.error != QJsonParseError::NoError || !doc.isObject()) {
        outError = QStringLiteral("Invalid control file JSON");
        return false;
    }

    QJsonObject obj = doc.object();
    if (!obj.contains(QStringLiteral("port")) || !obj.contains(QStringLiteral("token"))) {
        outError = QStringLiteral("Control file missing port or token");
        return false;
    }

    QJsonValue portVal = obj.value(QStringLiteral("port"));
    if (!validatePort(portVal)) {
        outError = QStringLiteral("Invalid numeric integral port in control file");
        return false;
    }

    QString token = obj.value(QStringLiteral("token")).toString();
    if (!validateToken(token)) {
        outError = QStringLiteral("Invalid token format in control file");
        return false;
    }

    outPort = portVal.toInt();
    outToken = token;
    return true;
}

ClientBridge::ClientBridge(const QString &overrideControlPath, bool checkRuntimeMode, QObject *parent)
    : QObject(parent)
    , m_overrideControlPath(overrideControlPath)
    , m_checkRuntimeMode(checkRuntimeMode)
{
    if (m_checkRuntimeMode) {
        m_controlFilePath = QStringLiteral("/dev/null");
        m_connectionStatus = QStringLiteral("Disconnected");
        return;
    }

    // Explicitly set NoProxy so user/system/env proxy cannot intercept loopback bearer token
    m_nam.setProxy(QNetworkProxy::NoProxy);

    m_controlFilePath = !m_overrideControlPath.isEmpty() ? m_overrideControlPath : resolveStandardControlPath();

    // Setup poll timeout timer (35 seconds: daemon internal timeout is 30s)
    m_pollTimeoutTimer.setSingleShot(true);
    m_pollTimeoutTimer.setInterval(35000);
    connect(&m_pollTimeoutTimer, &QTimer::timeout, this, &ClientBridge::onStatePollTimeout);

    // Setup backoff timer
    m_backoffTimer.setSingleShot(true);
    connect(&m_backoffTimer, &QTimer::timeout, this, &ClientBridge::onBackoffTimerFired);

    // Setup reconnect / reload timer (polls control file every 3 seconds if disconnected or changed)
    m_reconnectTimer.setInterval(3000);
    connect(&m_reconnectTimer, &QTimer::timeout, this, &ClientBridge::onReconnectTimerFired);
    m_reconnectTimer.start();

    // Setup file watcher
    connect(&m_fileWatcher, &QFileSystemWatcher::fileChanged, this, &ClientBridge::onControlFileWatchTriggered);
    connect(&m_fileWatcher, &QFileSystemWatcher::directoryChanged, this, &ClientBridge::onControlFileWatchTriggered);
    setupWatcher();

    // Initial load
    reloadControlFile();
}

ClientBridge::~ClientBridge() {
    m_pollTimeoutTimer.stop();
    m_backoffTimer.stop();
    m_reconnectTimer.stop();
    QNetworkReply *replyToAbort = m_pollReply;
    m_pollReply = nullptr;
    if (replyToAbort) {
        replyToAbort->disconnect(this);
        replyToAbort->abort();
        replyToAbort->deleteLater();
    }
}

void ClientBridge::setWindowActive(bool active) {
    if (m_windowActive == active) return;
    m_windowActive = active;
    emit windowActiveChanged();

    if (m_windowActive && m_connected && !m_selectedDeviceId.isEmpty()) {
        checkAndMarkRead(m_selectedDeviceId);
    }
}

void ClientBridge::setupWatcher() {
    QStringList paths = m_fileWatcher.files() + m_fileWatcher.directories();
    if (!paths.isEmpty()) {
        m_fileWatcher.removePaths(paths);
    }

    QFileInfo fi(m_controlFilePath);
    QDir dir = fi.dir();
    if (dir.exists()) {
        m_fileWatcher.addPath(dir.absolutePath());
    }
    if (fi.exists()) {
        m_fileWatcher.addPath(fi.absoluteFilePath());
    }
}

void ClientBridge::onControlFileWatchTriggered(const QString &) {
    setupWatcher();
    reloadControlFile();
}

void ClientBridge::onReconnectTimerFired() {
    reloadControlFile();
}

void ClientBridge::checkControlFileNow() {
    setupWatcher();
    reloadControlFile();
}

void ClientBridge::setConnectionStatus(bool connected, const QString &status) {
    bool changed = (m_connected != connected || m_connectionStatus != status);
    m_connected = connected;
    m_connectionStatus = status;
    if (changed) {
        emit connectionChanged();
    }
}

void ClientBridge::reloadControlFile() {
    int newPort = 0;
    QString newToken;
    QString err;

    if (!validateControlFile(m_controlFilePath, newPort, newToken, err)) {
        // Clear configured credentials on missing/invalid metadata
        m_port = 0;
        m_token.clear();
        m_pollTimeoutTimer.stop();
        m_backoffTimer.stop();
        QNetworkReply *replyToAbort = m_pollReply;
        m_pollReply = nullptr;
        if (replyToAbort) {
            replyToAbort->disconnect(this);
            replyToAbort->abort();
            replyToAbort->deleteLater();
        }
        handleDisconnect(err);
        return;
    }

    // Check if credentials changed (credential rotation)
    if (newPort != m_port || newToken != m_token) {
        handleDisconnect(QStringLiteral("Credentials changed"));

        m_port = newPort;
        m_token = newToken;
        m_sessionGeneration++;
        m_stateVersion = 0;
        m_backoffMs = MIN_BACKOFF_MS;
        setConnectionStatus(false, QStringLiteral("Connecting..."));

        pollState();
    } else if (!m_connected && m_pollReply == nullptr && !m_backoffTimer.isActive()) {
        pollState();
    }
}

void ClientBridge::handleDisconnect(const QString &reason) {
    m_sessionGeneration++;

    m_pollTimeoutTimer.stop();
    m_backoffTimer.stop();
    QNetworkReply *replyToAbort = m_pollReply;
    m_pollReply = nullptr;
    if (replyToAbort) {
        replyToAbort->disconnect(this);
        replyToAbort->abort();
        replyToAbort->deleteLater();
    }
    m_pollBuffer.clear();
    m_inFlightActions = 0;
    if (m_sendingText) {
        m_sendingText = false;
        emit sendingTextChanged();
    }

    // Clear stale state, selection, prompts, history
    m_selfDevice = QJsonObject();
    m_pairedDevices = QJsonArray();
    m_nearbyDevices = QJsonArray();
    m_pairingDialog = QJsonObject();
    m_incomingRequests = QJsonArray();
    m_transfers = QJsonArray();
    m_notifications = QJsonArray();
    m_qrShare = QJsonObject();
    m_update = QJsonObject();
    m_activeQrShareResult = QJsonObject();
    emit updateStateChanged();

    m_selectedDeviceId.clear();
    m_selectedDeviceName.clear();
    m_historyMessages = QJsonArray();
    m_nextBeforeId = QJsonValue();
    m_hasOlderHistory = false;
    m_loadingHistory = false;
    m_loadingOlder = false;
    m_inFlightReadDevices.clear();

    setConnectionStatus(false, reason.isEmpty() ? QStringLiteral("Disconnected") : reason);

    emit sessionReset();
    emit stateUpdated();
    emit selectedDeviceChanged();
    emit historyChanged();
    emit historyLoadingChanged();
    emit qrShareResultChanged();
}

QNetworkRequest ClientBridge::createRequest(const QString &path) const {
    QUrl url;
    url.setScheme(QStringLiteral("http"));
    url.setHost(QStringLiteral("127.0.0.1"));
    url.setPort(m_port);
    url.setPath(path.section(QLatin1Char('?'), 0, 0));
    if (path.contains(QLatin1Char('?'))) {
        url.setQuery(path.section(QLatin1Char('?'), 1));
    }

    QNetworkRequest req(url);
    // Explicitly reject automatic redirects
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    // Authenticated request
    req.setRawHeader(QByteArrayLiteral("Authorization"), QByteArrayLiteral("Bearer ") + m_token.toUtf8());
    return req;
}

void ClientBridge::pollState() {
    if (m_port <= 0 || m_token.isEmpty()) {
        return;
    }
    if (m_pollReply != nullptr) {
        return;
    }

    quint64 sessionGen = m_sessionGeneration;
    m_pollGeneration = sessionGen;
    m_pollBuffer.clear();

    QString path = m_stateVersion > 0
        ? QStringLiteral("/state?since=%1").arg(m_stateVersion)
        : QStringLiteral("/state");

    QNetworkRequest req = createRequest(path);
    QNetworkReply *reply = m_nam.get(req);
    m_pollReply = reply;
    m_pollTimeoutTimer.start();

    connect(reply, &QNetworkReply::readyRead, this, [this, reply, sessionGen]() {
        if (m_pollReply != reply || m_sessionGeneration != sessionGen) {
            return;
        }
        m_pollBuffer.append(reply->readAll());
        if (m_pollBuffer.size() > MAX_REPLY_BODY_BYTES) {
            reply->abort();
        }
    });

    connect(reply, &QNetworkReply::finished, this, [this, reply, sessionGen]() {
        onStatePollFinished(reply, sessionGen);
    });
}

void ClientBridge::onStatePollTimeout() {
    if (m_pollReply) {
        QNetworkReply *replyToAbort = m_pollReply;
        m_pollReply = nullptr;
        replyToAbort->disconnect(this);
        replyToAbort->abort();
        replyToAbort->deleteLater();
        setConnectionStatus(false, QStringLiteral("Poll timeout"));
        m_backoffTimer.start(m_backoffMs);
        m_backoffMs = qMin(m_backoffMs * 2, MAX_BACKOFF_MS);
    }
}

void ClientBridge::onStatePollFinished(QNetworkReply *reply, quint64 sessionGen) {
    if (!reply) {
        return;
    }

    if (m_pollReply == reply) {
        m_pollTimeoutTimer.stop();
        m_pollReply = nullptr;
    }

    reply->deleteLater();

    if (sessionGen != m_sessionGeneration) {
        m_pollBuffer.clear();
        return;
    }

    int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();

    // Reject 3xx redirects
    if (httpCode >= 300 && httpCode < 400) {
        m_pollBuffer.clear();
        setConnectionStatus(false, QStringLiteral("HTTP redirect rejected (%1)").arg(httpCode));
        m_backoffTimer.start(m_backoffMs);
        m_backoffMs = qMin(m_backoffMs * 2, MAX_BACKOFF_MS);
        return;
    }

    if (reply->error() != QNetworkReply::NoError || httpCode < 200 || httpCode >= 300) {
        m_pollBuffer.clear();
        QString errStr = httpCode > 0 ? QStringLiteral("HTTP %1").arg(httpCode) : reply->errorString();
        setConnectionStatus(false, QStringLiteral("Connection error (%1)").arg(errStr));

        m_backoffTimer.start(m_backoffMs);
        m_backoffMs = qMin(m_backoffMs * 2, MAX_BACKOFF_MS);

        reloadControlFile();
        return;
    }

    QByteArray data = m_pollBuffer;
    m_pollBuffer.clear();

    QJsonParseError parseErr;
    QJsonDocument doc = QJsonDocument::fromJson(data, &parseErr);
    if (parseErr.error != QJsonParseError::NoError || !doc.isObject()) {
        setConnectionStatus(false, QStringLiteral("Malformed response from daemon"));
        m_backoffTimer.start(m_backoffMs);
        m_backoffMs = qMin(m_backoffMs * 2, MAX_BACKOFF_MS);
        return;
    }

    QJsonObject root = doc.object();
    if (!root.value(QStringLiteral("ok")).toBool()) {
        m_backoffTimer.start(m_backoffMs);
        m_backoffMs = qMin(m_backoffMs * 2, MAX_BACKOFF_MS);
        return;
    }

    m_backoffMs = MIN_BACKOFF_MS;
    setConnectionStatus(true, QStringLiteral("Connected"));

    qint64 incomingVersion = root.value(QStringLiteral("version")).toInteger();
    bool versionChanged = incomingVersion != m_stateVersion;
    m_stateVersion = incomingVersion;

    m_selfDevice = root.value(QStringLiteral("self")).toObject();

    QJsonObject settingsObj = root.value(QStringLiteral("settings")).toObject();
    m_supportsBackgroundDiscovery = settingsObj.value(QStringLiteral("supportsBackgroundDiscovery")).toBool(false);
    m_backgroundDiscoveryEnabled = settingsObj.value(QStringLiteral("backgroundDiscoveryEnabled")).toBool(true);

    QJsonArray allDevices = root.value(QStringLiteral("devices")).toArray();
    QJsonArray paired;
    QJsonArray nearby;
    for (const QJsonValue &val : allDevices) {
        QJsonObject d = val.toObject();
        QString trust = d.value(QStringLiteral("trustStatus")).toString();
        if (trust == QStringLiteral("trusted")) {
            paired.append(d);
        } else {
            nearby.append(d);
        }
    }
    m_pairedDevices = paired;
    m_nearbyDevices = nearby;

    m_pairingDialog = root.value(QStringLiteral("pairingDialog")).toObject();
    m_incomingRequests = root.value(QStringLiteral("incoming")).toArray();
    m_transfers = root.value(QStringLiteral("transfers")).toArray();
    m_notifications = root.value(QStringLiteral("notifications")).toArray();
    m_qrShare = root.value(QStringLiteral("qrShare")).toObject();

    QJsonObject updateObj = root.value(QStringLiteral("update")).toObject();
    if (updateObj != m_update) {
        m_update = updateObj;
        emit updateStateChanged();
    }

    bool qrActive = m_qrShare.value(QStringLiteral("active")).toBool(false);
    if (!qrActive && !m_activeQrShareResult.isEmpty()) {
        m_activeQrShareResult = QJsonObject();
        emit qrShareResultChanged();
    }

    emit stateUpdated();

    if (!m_selectedDeviceId.isEmpty()) {
        bool stillPaired = false;
        for (const QJsonValue &val : m_pairedDevices) {
            if (val.toObject().value(QStringLiteral("deviceId")).toString() == m_selectedDeviceId) {
                stillPaired = true;
                break;
            }
        }
        if (!stillPaired) {
            clearSelection();
        } else if (versionChanged) {
            syncLatestMessagesForSelectedDevice(++m_historySeq);
        }
    }

    if (!versionChanged) {
        QTimer::singleShot(500, this, &ClientBridge::pollState);
    } else {
        pollState();
    }
}

void ClientBridge::onBackoffTimerFired() {
    if (m_port > 0 && !m_token.isEmpty() && m_pollReply == nullptr) {
        pollState();
    }
}

void ClientBridge::sendActionRequest(const QString &method, const QString &path, const QJsonObject &body,
                                     const std::function<void(bool success, const QJsonObject &response, const QString &error)> &callback) {
    if (!m_connected || m_port <= 0 || m_token.isEmpty()) {
        QString err = QStringLiteral("Not connected to Klardrop daemon");
        m_lastError = err;
        emit errorOccurred(err);
        if (callback) callback(false, QJsonObject(), err);
        return;
    }

    if (m_inFlightActions >= MAX_CONCURRENT_ACTIONS) {
        QString err = QStringLiteral("Too many concurrent requests");
        m_lastError = err;
        emit errorOccurred(err);
        if (callback) callback(false, QJsonObject(), err);
        return;
    }

    m_inFlightActions++;
    quint64 sessionGen = m_sessionGeneration;

    QNetworkRequest req = createRequest(path);
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));

    QByteArray payloadData;
    if (!body.isEmpty() || method == QStringLiteral("POST")) {
        payloadData = QJsonDocument(body).toJson(QJsonDocument::Compact);
    }

    QNetworkReply *reply = nullptr;
    if (method == QStringLiteral("POST")) {
        reply = m_nam.post(req, payloadData);
    } else if (method == QStringLiteral("GET")) {
        reply = m_nam.get(req);
    } else {
        reply = m_nam.sendCustomRequest(req, method.toUtf8(), payloadData);
    }

    // Bounded action timeout (10s)
    auto timeoutTimer = new QTimer(reply);
    timeoutTimer->setSingleShot(true);
    timeoutTimer->setInterval(10000);
    connect(timeoutTimer, &QTimer::timeout, reply, [reply]() {
        reply->abort();
    });
    timeoutTimer->start();

    // Memory-bounded response reading (cap at 4 MB during reception)
    auto responseBuffer = new QByteArray();
    connect(reply, &QNetworkReply::readyRead, reply, [reply, responseBuffer]() {
        responseBuffer->append(reply->readAll());
        if (responseBuffer->size() > MAX_REPLY_BODY_BYTES) {
            reply->abort();
        }
    });

    connect(reply, &QNetworkReply::finished, this, [this, path, reply, responseBuffer, sessionGen, callback]() {
        QByteArray data = *responseBuffer;
        delete responseBuffer;
        reply->deleteLater();

        if (sessionGen != m_sessionGeneration) {
            return;
        }

        m_inFlightActions = qMax(0, m_inFlightActions - 1);

        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();

        // Reject 3xx redirects
        if (httpCode >= 300 && httpCode < 400) {
            QString errMsg = QStringLiteral("HTTP redirect rejected (%1)").arg(httpCode);
            m_lastError = errMsg;
            emit errorOccurred(errMsg);
            if (callback) callback(false, QJsonObject(), errMsg);
            return;
        }

        if (reply->error() != QNetworkReply::NoError || httpCode < 200 || httpCode >= 300) {
            QString errMsg;
            QJsonDocument errDoc = QJsonDocument::fromJson(data);
            if (errDoc.isObject() && errDoc.object().contains(QStringLiteral("error"))) {
                errMsg = errDoc.object().value(QStringLiteral("error")).toString();
            } else if (httpCode > 0) {
                errMsg = QStringLiteral("HTTP %1").arg(httpCode);
            } else {
                errMsg = reply->errorString();
            }
            if (path == QStringLiteral("/update/apply") && httpCode == 409) {
                if (!errMsg.isEmpty() && !errMsg.endsWith(QLatin1Char('.'))) {
                    errMsg += QStringLiteral(".");
                }
                if (!errMsg.isEmpty()) {
                    errMsg += QStringLiteral(" ");
                }
                errMsg += QStringLiteral("Wait for active transfers to finish, then retry.");
            }
            m_lastError = errMsg;
            emit errorOccurred(errMsg);
            if (callback) callback(false, QJsonObject(), errMsg);
            return;
        }

        QJsonParseError parseErr;
        QJsonDocument doc = QJsonDocument::fromJson(data, &parseErr);
        if (parseErr.error != QJsonParseError::NoError || !doc.isObject()) {
            QString errMsg = QStringLiteral("Malformed response from daemon");
            m_lastError = errMsg;
            emit errorOccurred(errMsg);
            if (callback) callback(false, QJsonObject(), errMsg);
            return;
        }

        QJsonObject resObj = doc.object();
        if (!resObj.value(QStringLiteral("ok")).toBool()) {
            QString errMsg = resObj.contains(QStringLiteral("error"))
                ? resObj.value(QStringLiteral("error")).toString()
                : QStringLiteral("Action returned ok: false");
            m_lastError = errMsg;
            emit errorOccurred(errMsg);
            if (callback) callback(false, resObj, errMsg);
            return;
        }

        // Shared validation for /send-text and /send-clipboard: require result EXACTLY "completed"
        if (path == QStringLiteral("/send-text") || path == QStringLiteral("/send-clipboard")) {
            QString result = resObj.value(QStringLiteral("result")).toString();
            if (result != QStringLiteral("completed")) {
                QString errMsg;
                if (result.startsWith(QStringLiteral("error:"))) {
                    errMsg = result.mid(6);
                } else if (result.startsWith(QStringLiteral("unknown:"))) {
                    errMsg = QStringLiteral("Send status unknown (%1)").arg(result.mid(8));
                } else if (result.isEmpty()) {
                    errMsg = QStringLiteral("Action missing completion result");
                } else {
                    errMsg = QStringLiteral("Send failed: %1").arg(result);
                }
                if (errMsg.isEmpty()) {
                    errMsg = QStringLiteral("Action failed");
                }
                m_lastError = errMsg;
                emit errorOccurred(errMsg);
                if (callback) callback(false, resObj, errMsg);
                return;
            }
        }

        if (callback) callback(true, resObj, QString());
    });
}

void ClientBridge::selectDevice(const QString &deviceId, const QString &deviceName) {
    if (m_selectedDeviceId == deviceId && !deviceId.isEmpty()) {
        return;
    }

    m_selectedDeviceId = deviceId;
    m_selectedDeviceName = deviceName.isEmpty() ? deviceId : deviceName;
    m_historyMessages = QJsonArray();
    m_nextBeforeId = QJsonValue();
    m_hasOlderHistory = false;
    m_deviceSelectSeq++;

    emit selectedDeviceChanged();
    emit historyChanged();

    if (deviceId.isEmpty()) {
        return;
    }

    m_loadingHistory = true;
    emit historyLoadingChanged();
    fetchHistoryPage(deviceId, QJsonValue(), false, ++m_historySeq);

    if (m_windowActive) {
        checkAndMarkRead(deviceId);
    }
}

void ClientBridge::clearSelection() {
    selectDevice(QString(), QString());
}

void ClientBridge::refreshHistory() {
    if (m_selectedDeviceId.isEmpty()) return;
    m_loadingHistory = true;
    emit historyLoadingChanged();
    fetchHistoryPage(m_selectedDeviceId, QJsonValue(), false, ++m_historySeq);
}

void ClientBridge::loadOlderHistory() {
    if (m_selectedDeviceId.isEmpty() || !m_hasOlderHistory || m_loadingOlder) return;
    m_loadingOlder = true;
    emit historyLoadingChanged();
    fetchHistoryPage(m_selectedDeviceId, m_nextBeforeId, true, ++m_historySeq);
}

void ClientBridge::fetchHistoryPage(const QString &deviceId, const QJsonValue &beforeId, bool isOlderPage, quint64 reqSeq) {
    quint64 sessionGen = m_sessionGeneration;
    quint64 devSeq = m_deviceSelectSeq;

    QString path = QStringLiteral("/history?device=%1&limit=50").arg(QUrl::toPercentEncoding(deviceId));
    if (!beforeId.isNull() && !beforeId.isUndefined()) {
        path += QStringLiteral("&before=%1").arg(beforeId.toInteger());
    }

    sendActionRequest(QStringLiteral("GET"), path, QJsonObject(), [this, deviceId, isOlderPage, sessionGen, devSeq, reqSeq](bool success, const QJsonObject &resp, const QString &) {
        if (sessionGen != m_sessionGeneration || devSeq != m_deviceSelectSeq || m_selectedDeviceId != deviceId) {
            return;
        }

        if (isOlderPage) {
            m_loadingOlder = false;
        } else {
            m_loadingHistory = false;
        }
        emit historyLoadingChanged();

        if (!success) {
            return;
        }

        QJsonArray msgs = resp.value(QStringLiteral("messages")).toArray();
        QJsonArray chronMsgs;
        for (int i = msgs.size() - 1; i >= 0; --i) {
            chronMsgs.append(msgs.at(i));
        }

        QJsonValue nextBefore = resp.value(QStringLiteral("nextBefore"));
        m_nextBeforeId = nextBefore;
        m_hasOlderHistory = !nextBefore.isNull() && !nextBefore.isUndefined();

        if (isOlderPage) {
            QJsonArray combined = chronMsgs;
            for (const QJsonValue &existing : m_historyMessages) {
                combined.append(existing);
            }
            // ponytail: loaded history grows with requested pages as the user navigates;
            // avoid pruning newly fetched older pages while advancing cursor. Upgrade to bounded window/ceiling if excessive memory growth is observed.
            m_historyMessages = combined;
        } else {
            if (reqSeq < m_latestAppliedHistorySeq) {
                return;
            }
            m_latestAppliedHistorySeq = reqSeq;
            m_historyMessages = chronMsgs;
        }

        emit historyChanged();
    });
}

void ClientBridge::syncLatestMessagesForSelectedDevice(quint64 reqSeq) {
    if (m_selectedDeviceId.isEmpty()) return;

    QString deviceId = m_selectedDeviceId;
    quint64 sessionGen = m_sessionGeneration;
    quint64 devSeq = m_deviceSelectSeq;

    QString path = QStringLiteral("/history?device=%1&limit=50").arg(QUrl::toPercentEncoding(deviceId));
    sendActionRequest(QStringLiteral("GET"), path, QJsonObject(), [this, deviceId, sessionGen, devSeq, reqSeq](bool success, const QJsonObject &resp, const QString &) {
        if (sessionGen != m_sessionGeneration || devSeq != m_deviceSelectSeq || m_selectedDeviceId != deviceId) {
            return;
        }
        if (!success) return;

        if (reqSeq < m_latestAppliedHistorySeq) {
            return;
        }
        m_latestAppliedHistorySeq = reqSeq;

        QJsonArray msgs = resp.value(QStringLiteral("messages")).toArray();
        QJsonArray chronNewest;
        for (int i = msgs.size() - 1; i >= 0; --i) {
            chronNewest.append(msgs.at(i));
        }

        QMap<qint64, int> idToIndex;
        for (int i = 0; i < m_historyMessages.size(); ++i) {
            qint64 id = m_historyMessages.at(i).toObject().value(QStringLiteral("id")).toInteger();
            idToIndex.insert(id, i);
        }

        bool hasNewMessages = false;
        for (const QJsonValue &val : chronNewest) {
            QJsonObject m = val.toObject();
            qint64 id = m.value(QStringLiteral("id")).toInteger();
            if (idToIndex.contains(id)) {
                m_historyMessages.replace(idToIndex.value(id), m);
            } else {
                m_historyMessages.append(m);
                hasNewMessages = true;
            }
        }

        emit historyChanged();

        if (hasNewMessages && m_windowActive) {
            checkAndMarkRead(deviceId);
        }
    });
}

void ClientBridge::checkAndMarkRead(const QString &deviceId) {
    if (deviceId.isEmpty() || !m_windowActive) return;

    bool hasUnread = false;
    for (const QJsonValue &val : m_pairedDevices) {
        QJsonObject d = val.toObject();
        if (d.value(QStringLiteral("deviceId")).toString() == deviceId) {
            hasUnread = d.value(QStringLiteral("hasUnread")).toBool(false) ||
                        d.value(QStringLiteral("unreadCount")).toInteger(0) > 0;
            break;
        }
    }
    if (hasUnread) {
        markHistoryRead(deviceId);
    }
}

void ClientBridge::markHistoryRead(const QString &deviceId) {
    if (deviceId.isEmpty() || !m_connected || !m_windowActive) return;

    // Deduplicate in-flight requests only: allow future unread marks
    if (m_inFlightReadDevices.contains(deviceId)) {
        return;
    }
    m_inFlightReadDevices.insert(deviceId);

    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/history/read"),
                      QJsonObject{{QStringLiteral("deviceId"), deviceId}},
                      [this, deviceId](bool, const QJsonObject &, const QString &) {
                          m_inFlightReadDevices.remove(deviceId);
                      });
}

void ClientBridge::pair(const QString &deviceId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/pair"),
                      QJsonObject{{QStringLiteral("deviceId"), deviceId}});
}

void ClientBridge::unpair(const QString &deviceId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/unpair"),
                      QJsonObject{{QStringLiteral("deviceId"), deviceId}},
                      [this, deviceId](bool success, const QJsonObject &, const QString &) {
                          if (success && m_selectedDeviceId == deviceId) {
                              clearSelection();
                          }
                      });
}

void ClientBridge::acceptPair(const QString &deviceId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/accept-pair"),
                      QJsonObject{{QStringLiteral("deviceId"), deviceId}});
}

void ClientBridge::rejectPair(const QString &deviceId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/reject-pair"),
                      QJsonObject{{QStringLiteral("deviceId"), deviceId}});
}

void ClientBridge::dismissPairingDialog() {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/pairing-dialog/dismiss"), QJsonObject());
}

void ClientBridge::acceptIncoming(int receiveId, const QString &deviceId) {
    QJsonObject payload;
    if (receiveId > 0) {
        payload.insert(QStringLiteral("receiveId"), receiveId);
    } else if (!deviceId.isEmpty()) {
        payload.insert(QStringLiteral("deviceId"), deviceId);
    }
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/accept-incoming"), payload);
}

void ClientBridge::rejectIncoming(int receiveId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/reject-incoming"),
                      QJsonObject{{QStringLiteral("receiveId"), receiveId}});
}

void ClientBridge::openIncoming(int receiveId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/incoming/open"),
                      QJsonObject{{QStringLiteral("receiveId"), receiveId}});
}

void ClientBridge::dismissIncoming(int receiveId) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/incoming/dismiss"),
                      QJsonObject{{QStringLiteral("receiveId"), receiveId}});
}

void ClientBridge::sendText(const QString &deviceId, const QString &text) {
    QString trimmed = text.trimmed();
    if (deviceId.isEmpty() || trimmed.isEmpty() || m_sendingText || !m_connected) return;

    m_sendingText = true;
    emit sendingTextChanged();

    quint64 sessionGen = m_sessionGeneration;
    quint64 devSeq = m_deviceSelectSeq;

    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/send-text"),
                      QJsonObject{
                          {QStringLiteral("deviceId"), deviceId},
                          {QStringLiteral("text"), trimmed}
                      },
                      [this, deviceId, text, sessionGen, devSeq](bool success, const QJsonObject &, const QString &) {
                          if (sessionGen == m_sessionGeneration) {
                              m_sendingText = false;
                              emit sendingTextChanged();
                          }

                          if (!success) {
                              return;
                          }

                          // textSent signal emit only under SAME captured sessionGeneration AND deviceSelectSeq/selecteddevice guard
                          if (sessionGen == m_sessionGeneration && devSeq == m_deviceSelectSeq && m_selectedDeviceId == deviceId) {
                              syncLatestMessagesForSelectedDevice(++m_historySeq);
                              emit textSent(deviceId, text);
                          }
                      });
}

void ClientBridge::sendClipboard(const QString &deviceId) {
    if (deviceId.isEmpty()) return;
    QString clip = QGuiApplication::clipboard()->text().trimmed();
    if (clip.isEmpty()) {
        m_lastError = QStringLiteral("Clipboard is empty");
        emit errorOccurred(m_lastError);
        return;
    }

    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/send-clipboard"),
                      QJsonObject{
                          {QStringLiteral("deviceId"), deviceId},
                          {QStringLiteral("text"), clip}
                      },
                      [this, deviceId](bool success, const QJsonObject &, const QString &) {
                          if (success && m_selectedDeviceId == deviceId) {
                              syncLatestMessagesForSelectedDevice(++m_historySeq);
                          }
                      });
}

void ClientBridge::sendFiles(const QString &deviceId, const QStringList &paths) {
    if (deviceId.isEmpty() || paths.isEmpty()) return;

    QJsonArray pathsArr;
    for (const QString &p : paths) {
        QFileInfo fi(p);
        if (!p.isEmpty() && fi.exists() && fi.isFile()) {
            pathsArr.append(fi.canonicalFilePath().isEmpty() ? p : fi.canonicalFilePath());
        }
    }
    if (pathsArr.isEmpty()) {
        m_lastError = QStringLiteral("No valid regular files to send");
        emit errorOccurred(m_lastError);
        return;
    }

    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/send-file"),
                      QJsonObject{
                          {QStringLiteral("deviceId"), deviceId},
                          {QStringLiteral("paths"), pathsArr}
                      },
                      [this, deviceId](bool success, const QJsonObject &, const QString &) {
                          if (success && m_selectedDeviceId == deviceId) {
                              syncLatestMessagesForSelectedDevice(++m_historySeq);
                          }
                      });
}

void ClientBridge::retryTransfer(qint64 fileTransferId) {
    if (fileTransferId <= 0) return;
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/retry"),
                      QJsonObject{{QStringLiteral("fileTransferId"), fileTransferId}},
                      [this](bool success, const QJsonObject &, const QString &) {
                          if (success && !m_selectedDeviceId.isEmpty()) {
                              syncLatestMessagesForSelectedDevice(++m_historySeq);
                          }
                      });
}

void ClientBridge::startQrShare(const QStringList &paths) {
    if (paths.isEmpty()) {
        m_lastError = QStringLiteral("No files selected for QR sharing");
        emit errorOccurred(m_lastError);
        return;
    }

    // Regular files only
    QJsonArray arr;
    for (const QString &p : paths) {
        QFileInfo fi(p);
        if (!fi.exists() || !fi.isFile()) {
            m_lastError = QStringLiteral("File not found or is not a regular file: %1").arg(p);
            emit errorOccurred(m_lastError);
            return;
        }
        arr.append(fi.canonicalFilePath().isEmpty() ? p : fi.canonicalFilePath());
    }

    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/qr-share"),
                      QJsonObject{{QStringLiteral("paths"), arr}},
                      [this](bool success, const QJsonObject &resp, const QString &err) {
                          if (success) {
                              m_activeQrShareResult = resp;
                              emit qrShareResultChanged();
                          } else {
                              m_activeQrShareResult = QJsonObject();
                              emit qrShareResultChanged();
                              m_lastError = err.isEmpty() ? QStringLiteral("Failed to start QR share") : err;
                              emit errorOccurred(m_lastError);
                          }
                      });
}

void ClientBridge::stopQrShare() {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/qr-share/stop"), QJsonObject());
    m_activeQrShareResult = QJsonObject();
    emit qrShareResultChanged();
}

void ClientBridge::setBackgroundDiscovery(bool enabled) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/settings"),
                      QJsonObject{{QStringLiteral("backgroundDiscoveryEnabled"), enabled}});
}

void ClientBridge::renameDevice(const QString &newName) {
    QString trimmed = newName.trimmed();
    if (trimmed.isEmpty()) return;
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/settings"),
                      QJsonObject{{QStringLiteral("name"), trimmed}});
}

void ClientBridge::dismissNotification(int id) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/notification/dismiss"),
                      QJsonObject{{QStringLiteral("id"), id}});
}

void ClientBridge::pairFromNotification(int id) {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/notification/pair"),
                      QJsonObject{{QStringLiteral("id"), id}});
}

void ClientBridge::copyToClipboard(const QString &text) {
    if (!text.isEmpty()) {
        QGuiApplication::clipboard()->setText(text);
    }
}

void ClientBridge::openLocalFile(const QString &path) {
    if (path.isEmpty()) return;
    QFileInfo fi(path);
    if (fi.exists()) {
        QDesktopServices::openUrl(QUrl::fromLocalFile(fi.canonicalFilePath().isEmpty() ? path : fi.canonicalFilePath()));
    }
}

void ClientBridge::revealLocalFile(const QString &path) {
    if (path.isEmpty()) return;
    QFileInfo fi(path);
    if (fi.exists()) {
        QDesktopServices::openUrl(QUrl::fromLocalFile(fi.dir().absolutePath()));
    }
}

QStringList ClientBridge::pickFilesToSend(const QString &deviceId) {
    if (deviceId.isEmpty() || !m_connected) return QStringList();
    quint64 sessionGen = m_sessionGeneration;
    QString expectedDev = m_selectedDeviceId;

    QStringList files = QFileDialog::getOpenFileNames(nullptr,
                                                      QStringLiteral("Select Files to Send"),
                                                      QDir::homePath());
    // Reject stale device if selection or session changed while picker was open
    if (m_sessionGeneration != sessionGen || m_selectedDeviceId != expectedDev || m_selectedDeviceId != deviceId) {
        return QStringList();
    }
    if (!files.isEmpty()) {
        sendFiles(deviceId, files);
    }
    return files;
}

QStringList ClientBridge::pickFilesForQrShare() {
    if (!m_connected) return QStringList();
    quint64 sessionGen = m_sessionGeneration;

    QStringList files = QFileDialog::getOpenFileNames(nullptr,
                                                      QStringLiteral("Select Files for QR Sharing"),
                                                      QDir::homePath());
    if (m_sessionGeneration != sessionGen) {
        return QStringList();
    }
    QStringList regularFiles;
    for (const QString &p : files) {
        QFileInfo fi(p);
        if (fi.exists() && fi.isFile()) {
            regularFiles.append(p);
        }
    }
    if (!regularFiles.isEmpty()) {
        startQrShare(regularFiles);
    }
    return regularFiles;
}

QString ClientBridge::cleanLocalFilePath(const QUrl &url) const {
    if (!url.isValid()) {
        return QString();
    }
    // Reject unexpected fragment or query on file URI
    if (url.hasFragment() || url.hasQuery()) {
        return QString();
    }
    // Reject non-file schemes
    if (!url.isLocalFile() && url.scheme() != QStringLiteral("file")) {
        return QString();
    }
    // Reject remote hosts (e.g. smb:// or file://remotehost/...)
    QString host = url.host();
    if (!host.isEmpty() && host != QStringLiteral("localhost") && host != QStringLiteral("127.0.0.1")) {
        return QString();
    }

    QString path = url.toLocalFile();
    if (path.isEmpty()) {
        return QString();
    }

    QFileInfo fi(path);
    if (fi.exists()) {
        return fi.canonicalFilePath().isEmpty() ? path : fi.canonicalFilePath();
    }
    return path;
}

QStringList ClientBridge::dropUrlsToPaths(const QList<QUrl> &urls) const {
    QStringList paths;
    for (const QUrl &u : urls) {
        QString clean = cleanLocalFilePath(u);
        if (!clean.isEmpty()) {
            QFileInfo fi(clean);
            if (fi.exists() && fi.isFile()) {
                paths.append(fi.canonicalFilePath().isEmpty() ? clean : fi.canonicalFilePath());
            }
        }
    }
    return paths;
}

QString ClientBridge::formatBytes(qint64 bytes) const {
    if (bytes <= 0) return QStringLiteral("0 B");
    if (bytes < 1024) return QStringLiteral("%1 B").arg(bytes);
    if (bytes < 1024 * 1024) return QStringLiteral("%1 KB").arg(QString::number(bytes / 1024.0, 'f', 1));
    if (bytes < 1024 * 1024 * 1024) return QStringLiteral("%1 MB").arg(QString::number(bytes / (1024.0 * 1024.0), 'f', 1));
    return QStringLiteral("%1 GB").arg(QString::number(bytes / (1024.0 * 1024.0 * 1024.0), 'f', 2));
}

QString ClientBridge::formatTimestampTime(qint64 millis) const {
    if (millis <= 0) return QString();
    return QDateTime::fromMSecsSinceEpoch(millis).toLocalTime().toString(QStringLiteral("hh:mm"));
}

QString ClientBridge::formatTimestampDate(qint64 millis) const {
    if (millis <= 0) return QString();
    QDate msgDate = QDateTime::fromMSecsSinceEpoch(millis).toLocalTime().date();
    QDate today = QDate::currentDate();
    if (msgDate == today) {
        return QStringLiteral("Today");
    }
    if (msgDate == today.addDays(-1)) {
        return QStringLiteral("Yesterday");
    }
    return msgDate.toString(QStringLiteral("MMM d, yyyy"));
}

QString ClientBridge::dayKey(qint64 millis) const {
    if (millis <= 0) return QString();
    return QDateTime::fromMSecsSinceEpoch(millis).toLocalTime().date().toString(QStringLiteral("yyyy-MM-dd"));
}

void ClientBridge::dismissError() {
    m_lastError.clear();
    emit errorOccurred(QString());
}

void ClientBridge::checkUpdate() {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/update/check"), QJsonObject());
}

void ClientBridge::applyUpdate() {
    sendActionRequest(QStringLiteral("POST"), QStringLiteral("/update/apply"), QJsonObject());
}

void ClientBridge::openUrl(const QUrl &url) {
    QString scheme = url.scheme().toLower();
    if (scheme == QStringLiteral("https") || scheme == QStringLiteral("http")) {
        QDesktopServices::openUrl(url);
    }
}
