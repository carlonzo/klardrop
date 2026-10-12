#include <QCoreApplication>
#include <QGuiApplication>
#include <QTimer>
#include <QQuickWindow>
#include <QLocalSocket>
#include <QStandardPaths>
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <unistd.h>
#include <iostream>

// TESTONLY Qt startup hook for IPC window activation regression test.
// Links production main.cpp, hides window once loaded, connects private
// IPC socket without sending data, and asserts the window is restored to visible.
static void runIpcReopenTest() {
    QTimer::singleShot(500, []() {
        QQuickWindow *targetWindow = nullptr;
        for (QWindow *w : QGuiApplication::topLevelWindows()) {
            if (auto *qw = qobject_cast<QQuickWindow *>(w)) {
                targetWindow = qw;
                break;
            }
        }
        if (!targetWindow) {
            std::cerr << "FAIL [IPC-TEST]: No top-level QQuickWindow found." << std::endl;
            QCoreApplication::exit(10);
            return;
        }

        // 1. Hide the window to simulate running in system tray
        targetWindow->hide();
        if (targetWindow->isVisible()) {
            std::cerr << "FAIL [IPC-TEST]: Window failed to hide." << std::endl;
            QCoreApplication::exit(11);
            return;
        }

        // Resolve socket path corresponding to this instance control path
        QString controlPath;
        const QStringList args = QCoreApplication::arguments();
        for (int i = 0; i < args.size(); ++i) {
            if ((args[i] == QStringLiteral("-c") || args[i] == QStringLiteral("--control-file")) && i + 1 < args.size()) {
                controlPath = args[i + 1];
                break;
            }
        }
        if (controlPath.isEmpty()) {
            QString runtimeDir = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
            if (!runtimeDir.isEmpty() && QFile::exists(runtimeDir + QStringLiteral("/klardrop/control.json"))) {
                controlPath = runtimeDir + QStringLiteral("/klardrop/control.json");
            } else {
                QString cacheDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
                controlPath = cacheDir + QStringLiteral("/klardrop/control.json");
            }
        }

        QString runtimeDir = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
        if (runtimeDir.isEmpty()) {
            runtimeDir = QDir::tempPath();
        }
        QByteArray pathHash = QCryptographicHash::hash(controlPath.toUtf8(), QCryptographicHash::Sha256).toHex().left(16);
        QString baseName = QStringLiteral("klardrop-qt-") + QString::number(::getuid()) + QStringLiteral("-") + QString::fromUtf8(pathHash);
        QString serverName = runtimeDir + QStringLiteral("/") + baseName + QStringLiteral(".sock");

        // 2. Connect private IPC socket without sending data (raw connect)
        auto *socket = new QLocalSocket(qApp);
        socket->connectToServer(serverName);
        if (!socket->waitForConnected(1000)) {
            std::cerr << "FAIL [IPC-TEST]: Failed to connect to IPC socket " << serverName.toStdString()
                      << ": " << socket->errorString().toStdString() << std::endl;
            QCoreApplication::exit(12);
            return;
        }

        // 3. Verify server accepted connection and activated/restored window
        QTimer::singleShot(300, [targetWindow, socket]() {
            if (!targetWindow->isVisible()) {
                std::cerr << "FAIL [IPC-TEST]: Window was not made visible after IPC socket connection!" << std::endl;
                QCoreApplication::exit(13);
                return;
            }
            std::cout << "[IPC-TEST] ✓ Window successfully restored to visible upon IPC socket connection without data." << std::endl;
            socket->disconnectFromServer();
            socket->deleteLater();
            QCoreApplication::exit(0);
        });
    });
}

Q_COREAPP_STARTUP_FUNCTION(runIpcReopenTest)
