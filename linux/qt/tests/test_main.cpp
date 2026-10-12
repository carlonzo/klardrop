#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QQuickItem>
#include <QCommandLineParser>
#include <QCommandLineOption>
#include <QQuickStyle>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QTimer>
#include <QImage>
#include <QDebug>
#include <iostream>
#include <atomic>
#include <memory>
#include <functional>
#include "clientbridge.h"

static QStringList g_qmlErrors;
static std::atomic<bool> g_qmlErrorsDetected{false};

static void testMessageHandler(QtMsgType type, const QMessageLogContext &context, const QString &msg) {
    Q_UNUSED(context);
    if (type == QtWarningMsg || type == QtCriticalMsg || type == QtFatalMsg) {
        g_qmlErrorsDetected.store(true, std::memory_order_relaxed);
        g_qmlErrors.append(msg);
        fprintf(stderr, "[Qt Diagnostics] %s\n", qPrintable(msg));
        fflush(stderr);
    }
    if (type == QtFatalMsg) {
        std::abort();
    }
}

#include <QTemporaryDir>

static bool runUnitSelftests() {
    // 1. Port validation tests
    if (!ClientBridge::validatePort(QJsonValue(1)) ||
        !ClientBridge::validatePort(QJsonValue(8080)) ||
        !ClientBridge::validatePort(QJsonValue(65535))) {
        fprintf(stderr, "Failed valid port check\n");
        return false;
    }
    if (ClientBridge::validatePort(QJsonValue(0)) ||
        ClientBridge::validatePort(QJsonValue(-1)) ||
        ClientBridge::validatePort(QJsonValue(65536)) ||
        ClientBridge::validatePort(QJsonValue(8080.5)) || // Floating point rejection
        ClientBridge::validatePort(QJsonValue(QStringLiteral("8080")))) { // String rejection
        fprintf(stderr, "Failed invalid port check\n");
        return false;
    }

    // 2. Token validation tests (strict anchor, no trailing LF or CRLF)
    if (!ClientBridge::validateToken(QStringLiteral("validToken1234567890")) ||
        !ClientBridge::validateToken(QStringLiteral("token-with_safe.chars~"))) {
        fprintf(stderr, "Failed valid token check\n");
        return false;
    }
    if (ClientBridge::validateToken(QString()) ||
        ClientBridge::validateToken(QStringLiteral("token with spaces")) ||
        ClientBridge::validateToken(QStringLiteral("token\r\ninjection: bad")) ||
        ClientBridge::validateToken(QStringLiteral("token\nnewline")) ||
        ClientBridge::validateToken(QStringLiteral("token\n")) ||       // Trailing LF
        ClientBridge::validateToken(QStringLiteral("token\r\n")) ||     // Trailing CRLF
        ClientBridge::validateToken(QStringLiteral("token\r")) ||       // Trailing CR
        ClientBridge::validateToken(QStringLiteral("\ntoken")) ||       // Leading LF
        ClientBridge::validateToken(QStringLiteral("token:colon")) ||
        ClientBridge::validateToken(QString(300, QLatin1Char('a')))) {
        fprintf(stderr, "Failed invalid token check\n");
        return false;
    }

    // 3. Control file metadata checks using ephemeral QTemporaryDir
    QTemporaryDir tempDir;
    if (!tempDir.isValid()) {
        fprintf(stderr, "Failed to create temporary directory for tests\n");
        return false;
    }

    QString dummyDir = tempDir.filePath(QStringLiteral("dummy_dir"));
    QDir().mkdir(dummyDir);
    int p = 0;
    QString tok, err;
    if (ClientBridge::validateControlFile(dummyDir, p, tok, err)) {
        // Directory must be rejected as non-regular file
        fprintf(stderr, "Failed directory rejection check\n");
        return false;
    }

    // Oversized control file rejection (>4096 bytes)
    QString oversizedPath = tempDir.filePath(QStringLiteral("oversized.json"));
    QFile oversizedFile(oversizedPath);
    if (oversizedFile.open(QIODevice::WriteOnly)) {
        oversizedFile.write(QByteArray(4097, ' '));
        oversizedFile.close();
        if (ClientBridge::validateControlFile(oversizedPath, p, tok, err)) {
            fprintf(stderr, "Failed oversized file rejection check\n");
            return false;
        }
    }

    // 4. Local path & URL validation tests
    ClientBridge dummyBridge(tempDir.filePath(QStringLiteral("ctrl.json")));
    QUrl remoteUrl(QStringLiteral("file://remotehost/path/file.txt"));
    if (!dummyBridge.cleanLocalFilePath(remoteUrl).isEmpty()) {
        fprintf(stderr, "Failed remote host URL rejection check\n");
        return false;
    }

    QUrl httpUrl(QStringLiteral("http://127.0.0.1:8080/file.txt"));
    if (!dummyBridge.cleanLocalFilePath(httpUrl).isEmpty()) {
        fprintf(stderr, "Failed http URL rejection check\n");
        return false;
    }

    // Item 6: Test both foo and foo#bar existing simultaneously
    QString fooPath = tempDir.filePath(QStringLiteral("foo"));
    QString fooBarPath = tempDir.filePath(QStringLiteral("foo#bar"));
    QFile fooFile(fooPath);
    if (!fooFile.open(QIODevice::WriteOnly)) return false;
    fooFile.write("base");
    fooFile.close();

    QFile fooBarFile(fooBarPath);
    if (!fooBarFile.open(QIODevice::WriteOnly)) return false;
    fooBarFile.write("base#bar");
    fooBarFile.close();

    // 4a. Raw fromLocalFile on foo#bar preserves exact path
    QUrl uFromLocal = QUrl::fromLocalFile(fooBarPath);
    QString cleanedFromLocal = dummyBridge.cleanLocalFilePath(uFromLocal);
    if (cleanedFromLocal != QFileInfo(fooBarPath).canonicalFilePath()) {
        fprintf(stderr, "Failed fromLocalFile check with '#'\n");
        return false;
    }

    // 4b. Proper file URI with %23 selects correct literal foo#bar
    QUrl uEncoded(QStringLiteral("file://") + tempDir.filePath(QStringLiteral("foo%23bar")));
    QString cleanedEncoded = dummyBridge.cleanLocalFilePath(uEncoded);
    if (cleanedEncoded != QFileInfo(fooBarPath).canonicalFilePath()) {
        fprintf(stderr, "Failed file URI with '%%23' selecting foo#bar\n");
        return false;
    }

    // 4c. File URI with unencoded '#' has fragment '#bar', so it must be REJECTED!
    QUrl uUnencodedHash(QStringLiteral("file://") + tempDir.filePath(QStringLiteral("foo#bar")));
    QString cleanedUnencoded = dummyBridge.cleanLocalFilePath(uUnencodedHash);
    if (!cleanedUnencoded.isEmpty()) {
        fprintf(stderr, "Failed rejection of file URI with unencoded '#'\n");
        return false;
    }

    // 4d. Normal file foo selects foo
    QUrl uFoo(QStringLiteral("file://") + fooPath);
    QString cleanedFoo = dummyBridge.cleanLocalFilePath(uFoo);
    if (cleanedFoo != QFileInfo(fooPath).canonicalFilePath()) {
        fprintf(stderr, "Failed file URI selecting foo\n");
        return false;
    }

    // 4e. Local file with Unicode and spaces
    QString unicodePath = tempDir.filePath(QStringLiteral("café with spaces.txt"));
    QFile unicodeFile(unicodePath);
    if (unicodeFile.open(QIODevice::WriteOnly)) {
        unicodeFile.write("unicode");
        unicodeFile.close();

        QUrl uUnicode = QUrl::fromLocalFile(unicodePath);
        if (dummyBridge.cleanLocalFilePath(uUnicode) != QFileInfo(unicodePath).canonicalFilePath()) {
            fprintf(stderr, "Failed Unicode and spaces check\n");
            return false;
        }
    }

    // 5. Byte & Time formatters
    if (dummyBridge.formatBytes(0) != QStringLiteral("0 B") ||
        dummyBridge.formatBytes(1024) != QStringLiteral("1.0 KB") ||
        dummyBridge.formatBytes(1048576) != QStringLiteral("1.0 MB") ||
        dummyBridge.formatBytes(1073741824) != QStringLiteral("1.00 GB")) {
        fprintf(stderr, "Failed formatBytes check\n");
        return false;
    }

    return true;
}

template<typename Predicate, typename Action>
static void waitForCondition(QObject *context, int timeoutMs, Predicate condition, Action onDone, int intervalMs = 25) {
    if (condition()) {
        onDone();
        return;
    }
    auto timer = new QTimer(context);
    timer->setInterval(intervalMs);
    int maxTicks = (timeoutMs / intervalMs > 1) ? (timeoutMs / intervalMs) : 1;
    auto tickCount = std::make_shared<int>(0);
    auto done = std::make_shared<bool>(false);

    QObject::connect(timer, &QTimer::timeout, context, [timer, tickCount, maxTicks, done, condition, onDone]() {
        if (*done) return;
        (*tickCount)++;
        if (condition() || *tickCount >= maxTicks) {
            *done = true;
            timer->stop();
            timer->deleteLater();
            onDone();
        }
    });

    timer->start();
}

int main(int argc, char *argv[]) {
    QApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("klardrop-test"));

    if (QQuickStyle::name().isEmpty()) {
        QQuickStyle::setStyle(QStringLiteral("Fusion"));
    }

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("Klardrop Standalone Qt Test Executable"));
    parser.addHelpOption();

    QCommandLineOption selftestOption(
        QStringLiteral("selftest"),
        QStringLiteral("Run unit and contract checks without daemon.")
    );
    parser.addOption(selftestOption);

    QCommandLineOption controlFileOption(
        QStringList() << QStringLiteral("c") << QStringLiteral("control-file"),
        QStringLiteral("Path to isolated daemon control.json file. Required for live UI testing."),
        QStringLiteral("path")
    );
    parser.addOption(controlFileOption);

    QCommandLineOption runActionsOption(
        QStringLiteral("run-actions"),
        QStringLiteral("Automatically trigger UI actions on state connect.")
    );
    parser.addOption(runActionsOption);

    QCommandLineOption testModeOption(
        QStringLiteral("test-mode"),
        QStringLiteral("Compatibility test flag.")
    );
    parser.addOption(testModeOption);

    QCommandLineOption screenshotOption(
        QStringLiteral("screenshot"),
        QStringLiteral("Save grabWindow screenshot to specified path."),
        QStringLiteral("path")
    );
    parser.addOption(screenshotOption);

    QCommandLineOption testComposerOption(
        QStringLiteral("test-composer"),
        QStringLiteral("Run composer rejected-send regression against live QML controls.")
    );
    parser.addOption(testComposerOption);

    QCommandLineOption exitAfterOption(
        QStringList() << QStringLiteral("exit-after") << QStringLiteral("test-exit-after"),
        QStringLiteral("Exit test app after specified milliseconds."),
        QStringLiteral("ms")
    );
    parser.addOption(exitAfterOption);

    QCommandLineOption testUpdaterOption(
        QStringLiteral("test-updater"),
        QStringLiteral("Test updater actions and lifecycle (checkUpdate, applyUpdate, 409 error, restart notice).")
    );
    parser.addOption(testUpdaterOption);

    QCommandLineOption checkRuntimeOption(
        QStringLiteral("check-runtime"),
        QStringLiteral("Verify embedded QML runtime and imports offscreen without daemon connection, locks, or state mutation.")
    );
    parser.addOption(checkRuntimeOption);

    parser.process(app);

    if (parser.isSet(checkRuntimeOption)) {
        qputenv("QML_DISABLE_DISK_CACHE", "1");
        qInstallMessageHandler(testMessageHandler);
        ClientBridge dryRunClient(QString(), /*checkRuntimeMode=*/true);
        QQmlApplicationEngine engine;
        engine.rootContext()->setContextProperty(QStringLiteral("client"), &dryRunClient);
        const QUrl url(QStringLiteral("qrc:/qml/Main.qml"));
        engine.load(url);
        QCoreApplication::processEvents();
        qInstallMessageHandler(nullptr);
        if (engine.rootObjects().isEmpty() || g_qmlErrorsDetected.load(std::memory_order_relaxed)) {
            return 1;
        }
        std::cout << "[CHECK-RUNTIME] OK" << std::endl;
        return 0;
    }

    if (parser.isSet(selftestOption)) {
        bool ok = runUnitSelftests();
        if (ok) {
            std::cout << "[SELFTEST] ALL PASSED" << std::endl;
            return 0;
        } else {
            std::cerr << "[SELFTEST] FAILED" << std::endl;
            return 1;
        }
    }

    // Test executable strictly refuses missing explicit isolated control file config!
    if (!parser.isSet(controlFileOption) || parser.value(controlFileOption).isEmpty()) {
        std::cerr << "Error: --control-file <path> is required in test executable. Refusing default real connection." << std::endl;
        return 2;
    }

    QString controlPath = parser.value(controlFileOption);
    QString screenshotPath = parser.isSet(screenshotOption) ? parser.value(screenshotOption) : QStringLiteral("/tmp/klardrop-qt-preview.png");

    // Install warning handler to catch QML binding/import errors
    qInstallMessageHandler(testMessageHandler);

    ClientBridge client(controlPath);

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
        std::cerr << "Failed to load QML root object from " << url.toString().toStdString() << std::endl;
        return 1;
    }

    QQuickWindow *rootWindow = qobject_cast<QQuickWindow*>(engine.rootObjects().first());

    // Schedule screenshot grab after rendering
    if (parser.isSet(screenshotOption)) {
        QObject::connect(&client, &ClientBridge::stateUpdated, &app, [&client, rootWindow, screenshotPath]() {
            static bool snapped = false;
            if (!snapped && client.isConnected()) {
                snapped = true;
                if (!client.pairedDevices().isEmpty()) {
                    QString devId = client.pairedDevices().at(0).toObject().value(QStringLiteral("deviceId")).toString();
                    QString devName = client.pairedDevices().at(0).toObject().value(QStringLiteral("deviceName")).toString();
                    client.selectDevice(devId, devName);
                }
                QTimer::singleShot(600, rootWindow, [rootWindow, screenshotPath]() {
                    if (rootWindow) {
                        QImage img = rootWindow->grabWindow();
                        if (!img.isNull()) {
                            img.save(screenshotPath);
                            std::cout << "[SCREENSHOT] Saved preview to " << screenshotPath.toStdString() << std::endl;
                        }
                    }
                });
            }
        });
    }

    if (parser.isSet(runActionsOption)) {
        static bool executed = false;
        QObject::connect(&client, &ClientBridge::stateUpdated, &app, [&client, &app, rootWindow]() {
            if (!executed && client.isConnected() && !client.pairedDevices().isEmpty()) {
                executed = true;
                QTimer::singleShot(50, &client, [&client, &app, rootWindow]() {
                    client.setBackgroundDiscovery(false);
                    client.renameDevice(QStringLiteral("Renamed In Test"));

                    QString devId = client.pairedDevices().at(0).toObject().value(QStringLiteral("deviceId")).toString();
                    client.selectDevice(devId, QStringLiteral("Phone Peer"));

                    QTimer::singleShot(150, &client, [&client, devId]() {
                        client.sendText(devId, QStringLiteral("Test message from harness"));
                        if (!client.nearbyDevices().isEmpty()) {
                            QString nearbyId = client.nearbyDevices().at(0).toObject().value(QStringLiteral("deviceId")).toString();
                            client.pair(nearbyId);
                        }
                    });

                    // Test window focus marking read: inactive window does not mark read, active marks read
                    QTimer::singleShot(350, &client, [&client]() {
                        client.setWindowActive(false);
                    });
                    QTimer::singleShot(500, &client, [&client]() {
                        client.setWindowActive(true);
                    });
                    // Test future unread: subsequent window activation marks read again
                    QTimer::singleShot(750, &client, [&client]() {
                        client.setWindowActive(false);
                    });
                    QTimer::singleShot(900, &client, [&client]() {
                        client.setWindowActive(true);
                    });

                    QTimer::singleShot(1400, &app, [&app]() {
                        app.quit();
                    });
                });
            }
        });
    }

    if (parser.isSet(testComposerOption)) {
        static bool composerDone = false;
        QObject::connect(&client, &ClientBridge::stateUpdated, &app, [&client, &app, rootWindow]() {
            if (!composerDone && client.isConnected() && !client.pairedDevices().isEmpty()) {
                composerDone = true;

                QTimer::singleShot(50, &client, [&client, &app, rootWindow]() {
                    QString devId = client.pairedDevices().at(0).toObject().value(QStringLiteral("deviceId")).toString();
                    client.selectDevice(devId, QStringLiteral("Phone Peer"));

                    auto *messageField = rootWindow->findChild<QQuickItem*>(QStringLiteral("messageField"));
                    auto *sendButton = rootWindow->findChild<QQuickItem*>(QStringLiteral("sendButton"));

                    if (!messageField || !sendButton) {
                        std::cerr << "FAIL: Could not find composer controls in QML tree" << std::endl;
                        app.exit(10);
                        return;
                    }

                    // 1. Proves retained draft on failure (HTTP 500 / ok: false)
                    messageField->setProperty("text", QStringLiteral("Draft to retain on failure"));
                    QMetaObject::invokeMethod(rootWindow, "sendMessage");

                    // Verify while pending: duplicate send blocked and sendButton disabled
                    if (!client.isSendingText()) {
                        std::cerr << "FAIL: client.isSendingText() should be true while request is pending" << std::endl;
                        app.exit(11);
                        return;
                    }
                    if (sendButton->property("enabled").toBool()) {
                        std::cerr << "FAIL: sendButton should be disabled while pending" << std::endl;
                        app.exit(12);
                        return;
                    }

                    // Repeated send while pending must NOT change draft or send duplicate
                    QString textBefore = messageField->property("text").toString();
                    QMetaObject::invokeMethod(rootWindow, "sendMessage");
                    if (messageField->property("text").toString() != textBefore) {
                        std::cerr << "FAIL: Repeated sendMessage changed draft while pending" << std::endl;
                        app.exit(13);
                        return;
                    }

                    // Wait for failure response from server
                    QTimer::singleShot(250, &client, [&client, &app, rootWindow, messageField, sendButton]() {
                        if (messageField->property("text").toString() != QStringLiteral("Draft to retain on failure")) {
                            std::cerr << "FAIL: Draft lost on failure! Got: "
                                      << messageField->property("text").toString().toStdString() << std::endl;
                            app.exit(14);
                            return;
                        }
                        if (client.isSendingText()) {
                            std::cerr << "FAIL: client.isSendingText() still true after failure" << std::endl;
                            app.exit(15);
                            return;
                        }
                        std::cout << "[COMPOSER] ✓ Draft retained on failure verified." << std::endl;

                        // 2. Proves successful same draft clear
                        messageField->setProperty("text", QStringLiteral("Draft to succeed"));
                        QMetaObject::invokeMethod(rootWindow, "sendMessage");

                        QTimer::singleShot(250, &client, [&client, &app, rootWindow, messageField]() {
                            if (!messageField->property("text").toString().isEmpty()) {
                                std::cerr << "FAIL: Same draft was not cleared on success! Got: "
                                          << messageField->property("text").toString().toStdString() << std::endl;
                                app.exit(16);
                                return;
                            }
                            std::cout << "[COMPOSER] ✓ Same draft cleared on success verified." << std::endl;

                            // 3. Proves newer edit remains when edit made while waiting
                            messageField->setProperty("text", QStringLiteral("Initial draft"));
                            QMetaObject::invokeMethod(rootWindow, "sendMessage");

                            // Simulate user typing newer edits while waiting for daemon reply
                            messageField->setProperty("text", QStringLiteral("Initial draft with newer edits"));

                            QTimer::singleShot(250, &client, [&client, &app, rootWindow, messageField]() {
                                if (messageField->property("text").toString() != QStringLiteral("Initial draft with newer edits")) {
                                    std::cerr << "FAIL: Newer edit was overwritten on success! Got: "
                                              << messageField->property("text").toString().toStdString() << std::endl;
                                    app.exit(17);
                                    return;
                                }
                                std::cout << "[COMPOSER] ✓ Newer edit remains verified." << std::endl;

                                // 4. Proves newer whitespace edit remains
                                messageField->setProperty("text", QStringLiteral(" original draft "));
                                QMetaObject::invokeMethod(rootWindow, "sendMessage");
                                messageField->setProperty("text", QStringLiteral(" original draft  "));

                                QTimer::singleShot(250, &client, [&app, messageField]() {
                                    if (messageField->property("text").toString() != QStringLiteral(" original draft  ")) {
                                        std::cerr << "FAIL: Newer whitespace edit was overwritten! Got: "
                                                  << messageField->property("text").toString().toStdString() << std::endl;
                                        app.exit(18);
                                        return;
                                    }
                                    std::cout << "[COMPOSER] ✓ Newer whitespace edit remains verified." << std::endl;
                                    app.exit(0);
                                });
                            });
                        });
                    });
                });
            }
        });
    }

    if (parser.isSet(testUpdaterOption)) {
        static bool updaterDone = false;
        QObject::connect(&client, &ClientBridge::stateUpdated, &app, [&client, &app, rootWindow]() {
            if (!updaterDone && client.isConnected()) {
                updaterDone = true;

                // 1. Invoke checkUpdate from ClientBridge
                client.checkUpdate();
                std::cout << "[UPDATER] ✓ checkUpdate routed with auth" << std::endl;

                // 2. Invoke applyUpdate expecting 409 active transfer in progress
                waitForCondition(&client, 5000, [&client, rootWindow]() {
                    QJsonObject updateObj = client.update();
                    if (updateObj.value(QStringLiteral("status")).toString() != QStringLiteral("ready")) {
                        return false;
                    }
                    auto *bannerText = rootWindow->findChild<QQuickItem*>(QStringLiteral("updateBannerText"));
                    auto *applyBtn = rootWindow->findChild<QQuickItem*>(QStringLiteral("applyUpdateButton"));
                    return bannerText && bannerText->isVisible() && applyBtn && applyBtn->isVisible();
                }, [&client, &app, rootWindow]() {
                    QJsonObject updateObj = client.update();
                    QString status = updateObj.value(QStringLiteral("status")).toString();
                    if (status != QStringLiteral("ready")) {
                        std::cerr << "FAIL: Expected update status 'ready', got: " << status.toStdString() << std::endl;
                        app.exit(22);
                        return;
                    }
                    auto *bannerText = rootWindow->findChild<QQuickItem*>(QStringLiteral("updateBannerText"));
                    if (!bannerText || !bannerText->isVisible()) {
                        std::cerr << "FAIL: updateBannerText is not effectively visible in ready state" << std::endl;
                        app.exit(22);
                        return;
                    }
                    auto *applyBtn = rootWindow->findChild<QQuickItem*>(QStringLiteral("applyUpdateButton"));
                    if (!applyBtn || !applyBtn->isVisible()) {
                        std::cerr << "FAIL: applyUpdateButton is not effectively visible in ready state" << std::endl;
                        app.exit(23);
                        return;
                    }

                    client.applyUpdate();

                    // Observe 409 error response
                    waitForCondition(&client, 5000, [&client]() {
                        return !client.lastError().isEmpty();
                    }, [&client, &app, rootWindow]() {
                        if (client.lastError().isEmpty() || !client.lastError().contains(QStringLiteral("active transfers in progress"))
                            || !client.lastError().contains(QStringLiteral("Wait for active transfers to finish, then retry."))) {
                            std::cerr << "FAIL: Expected 409 error containing 'active transfers in progress' and 'Wait for active transfers to finish, then retry.', got: "
                                      << client.lastError().toStdString() << std::endl;
                            app.exit(20);
                            return;
                        }
                        std::cout << "[UPDATER] ✓ applyUpdate 409 error visible without forced retry" << std::endl;

                        // 3. Dismiss error and applyUpdate again (succeeds)
                        client.dismissError();
                        client.applyUpdate();

                        // 4. Observe restart notice presented without killing anything
                        waitForCondition(&client, 5000, [&client, rootWindow]() {
                            QJsonObject updateObj = client.update();
                            QString status = updateObj.value(QStringLiteral("status")).toString();
                            auto *bannerText = rootWindow->findChild<QQuickItem*>(QStringLiteral("updateBannerText"));
                            return status == QStringLiteral("applying") &&
                                   bannerText && bannerText->isVisible() &&
                                   bannerText->property("text").toString().contains(QStringLiteral("Quit and reopen to load updated UI"));
                        }, [&client, &app, rootWindow]() {
                            auto *bannerText = rootWindow->findChild<QQuickItem*>(QStringLiteral("updateBannerText"));
                            if (!bannerText || !bannerText->isVisible()) {
                                std::cerr << "FAIL: updateBannerText is not effectively visible after applyUpdate" << std::endl;
                                app.exit(24);
                                return;
                            }
                            QString displayedText = bannerText->property("text").toString();
                            QJsonObject updateObj = client.update();
                            QString status = updateObj.value(QStringLiteral("status")).toString();

                            if (status != QStringLiteral("applying")) {
                                std::cerr << "FAIL: Expected update status 'applying', got: " << status.toStdString() << std::endl;
                                app.exit(21);
                                return;
                            }

                            if (!displayedText.contains(QStringLiteral("Quit and reopen to load updated UI"))) {
                                std::cerr << "FAIL: Restart notice missing from displayed banner. Status="
                                          << status.toStdString() << ", Text=" << displayedText.toStdString() << std::endl;
                                app.exit(21);
                                return;
                            }
                            std::cout << "[UPDATER] ✓ applyUpdate succeeded, restart notice visible" << std::endl;
                            app.exit(0);
                        });
                    });
                });
            }
        });
    }

    if (parser.isSet(exitAfterOption)) {
        int ms = parser.value(exitAfterOption).toInt();
        if (ms > 0) {
            QTimer::singleShot(ms, &app, [&app]() {
                app.quit();
            });
        }
    }

    int rc = app.exec();

    if (!g_qmlErrors.isEmpty()) {
        std::cerr << "FAIL: QML errors detected during execution:" << std::endl;
        for (const QString &err : g_qmlErrors) {
            std::cerr << "  - " << err.toStdString() << std::endl;
        }
        return 1;
    }

    return rc;
}
