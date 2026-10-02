#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QEvent>
#include <QCommandLineParser>
#include <QCommandLineOption>
#include <QQuickStyle>
#include <QIcon>
#include <QSystemTrayIcon>
#include <QMenu>
#include <QAction>
#include <QLocalServer>
#include <QLocalSocket>
#include <QCryptographicHash>
#include <QLockFile>
#include <QStandardPaths>
#include <QDir>
#include <QThread>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "clientbridge.h"

#ifndef KLARDROP_VERSION
#define KLARDROP_VERSION "1.0.0"
#endif

static std::atomic<bool> g_runtimeCheckFailed{false};

static void runtimeCheckMessageHandler(QtMsgType type, const QMessageLogContext &context, const QString &msg) {
    Q_UNUSED(context);
    if (type == QtWarningMsg || type == QtCriticalMsg || type == QtFatalMsg) {
        g_runtimeCheckFailed.store(true, std::memory_order_relaxed);
    }
    // Always print exact diagnostics to stderr including missing imports and QML warnings
    fprintf(stderr, "%s\n", qPrintable(msg));
    fflush(stderr);
    if (type == QtFatalMsg) {
        std::abort();
    }
}

class WindowCloseFilter : public QObject {
public:
    WindowCloseFilter(QSystemTrayIcon *tray, QObject *parent = nullptr)
        : QObject(parent), m_tray(tray) {}

protected:
    bool eventFilter(QObject *obj, QEvent *event) override {
        if (event->type() == QEvent::Close) {
            if (QSystemTrayIcon::isSystemTrayAvailable() && m_tray && m_tray->isVisible()) {
                event->ignore();
                if (auto *w = qobject_cast<QWindow *>(obj)) {
                    w->hide();
                }
                return true;
            }
        }
        return QObject::eventFilter(obj, event);
    }

private:
    QSystemTrayIcon *m_tray;
};

int main(int argc, char *argv[]) {
    // If running in runtime-check mode, force offscreen/software backend early before QPA initialization
    bool checkRuntimeRequested = false;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--check-runtime") == 0) {
            checkRuntimeRequested = true;
            qputenv("QT_QPA_PLATFORM", "offscreen");
            qputenv("QT_QUICK_BACKEND", "software");
            qputenv("QML_DISABLE_DISK_CACHE", "1");
            break;
        }
    }

    QApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("klardrop-qt"));
    app.setApplicationDisplayName(QStringLiteral("Klardrop"));
    app.setOrganizationDomain(QStringLiteral("klardrop.carlom.com"));
    app.setOrganizationName(QStringLiteral("Klardrop"));
    app.setApplicationVersion(QStringLiteral(KLARDROP_VERSION));

    if (QQuickStyle::name().isEmpty()) {
        QQuickStyle::setStyle(QStringLiteral("Fusion"));
    }

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("Klardrop Standalone Qt Quick UI"));
    parser.addHelpOption();
    parser.addVersionOption();

    QCommandLineOption controlFileOption(
        QStringList() << QStringLiteral("c") << QStringLiteral("control-file"),
        QStringLiteral("Path to isolated daemon control.json file (overrides standard search path)."),
        QStringLiteral("path")
    );
    parser.addOption(controlFileOption);

    QCommandLineOption checkRuntimeOption(
        QStringLiteral("check-runtime"),
        QStringLiteral("Verify embedded QML runtime and imports offscreen without daemon connection, locks, or state mutation.")
    );
    parser.addOption(checkRuntimeOption);

    parser.process(app);

    // 1. Runtime-check mode: load QML offscreen, verify imports, never resolve control.json/connect API/use locks, exit bounded.
    if (parser.isSet(checkRuntimeOption) || checkRuntimeRequested) {
        qputenv("QML_DISABLE_DISK_CACHE", "1");
        qInstallMessageHandler(runtimeCheckMessageHandler);
        ClientBridge dryRunClient(QString(), /*checkRuntimeMode=*/true);
        QQmlApplicationEngine engine;
        engine.rootContext()->setContextProperty(QStringLiteral("client"), &dryRunClient);
        const QUrl url(QStringLiteral("qrc:/qml/Main.qml"));
        engine.load(url);
        QCoreApplication::processEvents();
        qInstallMessageHandler(nullptr);
        if (engine.rootObjects().isEmpty() || g_runtimeCheckFailed.load(std::memory_order_relaxed)) {
            return 1;
        }
        return 0;
    }

    QString overrideControlPath = parser.value(controlFileOption);
    QString controlPath = !overrideControlPath.isEmpty() ? overrideControlPath : ClientBridge::resolveStandardControlPath();

    // 2. Single-instance activation via local IPC & atomic QLockFile (control-file isolated sessions distinct)
    QString runtimeDir = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
    if (runtimeDir.isEmpty()) {
        runtimeDir = QDir::tempPath();
    }
    QByteArray pathHash = QCryptographicHash::hash(controlPath.toUtf8(), QCryptographicHash::Sha256).toHex().left(16);
    QString baseName = QStringLiteral("klardrop-qt-") + QString::number(::getuid()) + QStringLiteral("-") + QString::fromUtf8(pathHash);
    QString serverName = runtimeDir + QStringLiteral("/") + baseName + QStringLiteral(".sock");
    QString lockFilePath = runtimeDir + QStringLiteral("/") + baseName + QStringLiteral(".lock");

    QLockFile lockFile(lockFilePath);
    lockFile.setStaleLockTime(0);

    if (!lockFile.tryLock(200)) {
        // Another instance holds the lock. Connect and activate existing UI.
        QLocalSocket clientSocket;
        bool connected = false;
        for (int attempt = 0; attempt < 10; ++attempt) {
            clientSocket.connectToServer(serverName);
            if (clientSocket.waitForConnected(200)) {
                connected = true;
                break;
            }
            QThread::msleep(100);
        }
        if (connected) {
            clientSocket.disconnectFromServer();
            return 0; // Activated existing UI instance, avoid duplicate UI/tray
        }
        qWarning("Another klardrop-qt instance holds lock '%s' but IPC activation to '%s' failed.",
                 qPrintable(lockFilePath), qPrintable(serverName));
        return 1;
    }

    // Atomic lockowner only cleans stale socket
    QLocalServer::removeServer(serverName);
    auto *ipcServer = new QLocalServer(&app);
    ipcServer->setSocketOptions(QLocalServer::UserAccessOption);
    if (!ipcServer->listen(serverName)) {
        QLocalServer::removeServer(serverName);
        if (!ipcServer->listen(serverName)) {
            qCritical("Failed to listen on IPC socket '%s': %s",
                      qPrintable(serverName), qPrintable(ipcServer->errorString()));
            return 1;
        }
    }

    // 3. Normal UI initialization
    ClientBridge client(overrideControlPath);

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("client"), &client);

    const QUrl url(QStringLiteral("qrc:/qml/Main.qml"));
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreated,
                     &app, [url](QObject *obj, const QUrl &objUrl) {
        if (!obj && url == objUrl) {
            QCoreApplication::exit(-1);
        }
    }, Qt::QueuedConnection);

    engine.load(url);

    if (engine.rootObjects().isEmpty()) {
        return 1;
    }

    auto showWindow = [&engine]() {
        for (QObject *obj : engine.rootObjects()) {
            if (auto *win = qobject_cast<QQuickWindow *>(obj)) {
                win->show();
                win->raise();
                win->requestActivate();
            }
        }
    };

    // IPC activation handler for repeated launcher clicks:
    // Activate immediately on each accepted private UserAccess socket connection, disconnect/delete it.
    QObject::connect(ipcServer, &QLocalServer::newConnection, [ipcServer, showWindow]() {
        while (QLocalSocket *incoming = ipcServer->nextPendingConnection()) {
            showWindow();
            incoming->disconnectFromServer();
            incoming->deleteLater();
        }
    });

    // 4. System Tray Icon (Open/Quit UI only; close hides only if usable tray, otherwise exits)
    QIcon appIcon = QIcon::fromTheme(QStringLiteral("klardrop"), QIcon(QStringLiteral(":/icons/klardrop.png")));
    app.setWindowIcon(appIcon);

    auto *tray = new QSystemTrayIcon(appIcon, &app);
    auto *trayMenu = new QMenu();
    QObject::connect(tray, &QObject::destroyed, trayMenu, &QObject::deleteLater);
    QObject::connect(&app, &QCoreApplication::aboutToQuit, trayMenu, &QObject::deleteLater);
    QAction *openAction = trayMenu->addAction(QStringLiteral("Open"));
    trayMenu->addSeparator();
    QAction *quitAction = trayMenu->addAction(QStringLiteral("Quit"));
    tray->setContextMenu(trayMenu);

    QObject::connect(openAction, &QAction::triggered, showWindow);
    QObject::connect(tray, &QSystemTrayIcon::activated, [showWindow](QSystemTrayIcon::ActivationReason reason) {
        if (reason == QSystemTrayIcon::Trigger || reason == QSystemTrayIcon::DoubleClick) {
            showWindow();
        }
    });
    // Quit UI only — does NOT restart or stop daemon
    QObject::connect(quitAction, &QAction::triggered, &app, &QCoreApplication::quit);

    if (QSystemTrayIcon::isSystemTrayAvailable()) {
        tray->show();
    }

    // Window close intercept: hide if usable tray is present, exit if not
    for (QObject *obj : engine.rootObjects()) {
        if (auto *win = qobject_cast<QQuickWindow *>(obj)) {
            win->installEventFilter(new WindowCloseFilter(tray, win));
        }
    }

    return app.exec();
}

