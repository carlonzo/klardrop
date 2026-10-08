QT += core gui widgets qml quick quickcontrols2 network

CONFIG += c++17

isEmpty(KLARDROP_VERSION) {
    KLARDROP_VERSION = 1.0.0
}
DEFINES += KLARDROP_VERSION=\\\"$$KLARDROP_VERSION\\\"

TEMPLATE = app

INCLUDEPATH += ..
VPATH += ..

test_ipc {
    TARGET = klardrop-ipc-test
    SOURCES += \
        ../main.cpp \
        test_ipc_hook.cpp \
        ../clientbridge.cpp
    DEFINES += TEST_IPC_HOOK
} else {
    TARGET = klardrop-test
    SOURCES += \
        test_main.cpp \
        ../clientbridge.cpp
}

HEADERS += \
    ../clientbridge.h

RESOURCES += \
    ../qml.qrc

QMAKE_CXXFLAGS += -Wall -Wextra
